/*
 * Copyright 2014 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matrix.androidsdk.data;

import com.google.gson.JsonObject;

import org.matrix.androidsdk.MXDataHandler;
import org.matrix.androidsdk.listeners.IMXEventListener;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.model.BannedUser;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.Message;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.RoomResponse;
import org.matrix.androidsdk.rest.model.TokensChunkResponse;
import org.matrix.androidsdk.rest.model.User;
import org.matrix.androidsdk.util.JsonUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class representing a room and the interactions we have with it.
 */
public class Room {

    /**
     * The direction from which an incoming event is considered.
     * <ul>
     * <li>FORWARDS for events coming down the live event stream</li>
     * <li>BACKWARDS for old events requested through pagination</li>
     * </ul>
     */
    public static enum EventDirection {
        /**
         * The direction for events coming down the live event stream.
         */
        FORWARDS,

        /**
         * The direction for old events requested through pagination.
         */
        BACKWARDS
    }

    private String mRoomId;
    private RoomState mLiveState = new RoomState();
    private RoomState mBackState = new RoomState();

    private DataRetriever mDataRetriever;
    private MXDataHandler mDataHandler;

    // Map to keep track of the listeners the client adds vs. the ones we actually register to the global data handler.
    // This is needed to find the right one when removing the listener.
    private Map<IMXEventListener, IMXEventListener> mEventListeners = new HashMap<IMXEventListener, IMXEventListener>();

    private boolean isPaginating = false;
    private boolean canStillPaginate = true;
    // This is used to block live events and history requests until the state is fully processed and ready
    private boolean isReady = false;

    public String getRoomId() {
        return this.mRoomId;
    }

    public void setRoomId(String roomId) {
        mRoomId = roomId;
        mLiveState.roomId = roomId;
        mBackState.roomId = roomId;
    }

    public RoomState getLiveState() {
        return mLiveState;
    }

    public Collection<RoomMember> getMembers() {
        return mLiveState.getMembers();
    }

    public void setMember(String userId, RoomMember member) {
        mLiveState.setMember(userId, member);
    }

    public RoomMember getMember(String userId) {
        return mLiveState.getMember(userId);
    }

    public String getTopic() {
        return this.mLiveState.topic;
    }

    public String getName(String selfUserId) {
        return mLiveState.getDisplayName(selfUserId);
    }

    public String getVisibility() {
        return mLiveState.visibility;
    }

    public void setVisibility(String visibility) {
        mLiveState.visibility = visibility;
    }

    /**
     * Set the data retriever for storage/server requests.
     * @param dataRetriever should be the main DataRetriever object
     */
    public void setDataRetriever(DataRetriever dataRetriever) {
        mDataRetriever = dataRetriever;
    }

    /**
     * Set the event listener to send back events to. This is typically the DataHandler for dispatching the events to listeners.
     * @param dataHandler should be the main data handler for dispatching back events to registered listeners.
     */
    public void setDataHandler(MXDataHandler dataHandler) {
        mDataHandler = dataHandler;
    }

    /**
     * Add an event listener to this room. Only events relative to the room will come down.
     * @param eventListener the event listener to add
     */
    public void addEventListener(final IMXEventListener eventListener) {
        // Create a global listener that we'll add to the data handler
        IMXEventListener globalListener = new MXEventListener() {
            @Override
            public void onPresenceUpdate(Event event, User user) {
                // Only pass event through if the user is a member of the room
                if (getMember(user.userId) != null) {
                    eventListener.onPresenceUpdate(event, user);
                }
            }

            @Override
            public void onLiveEvent(Event event, RoomState roomState) {
                // Filter out events for other rooms and events while we are joining (before the room is ready)
                if (mRoomId.equals(event.roomId) && isReady) {
                    eventListener.onLiveEvent(event, roomState);
                }
            }

            @Override
            public void onBackEvent(Event event, RoomState roomState) {
                // Filter out events for other rooms
                if (mRoomId.equals(event.roomId)) {
                    eventListener.onBackEvent(event, roomState);
                }
            }
        };
        mEventListeners.put(eventListener, globalListener);
        mDataHandler.addListener(globalListener);
    }

    /**
     * Remove an event listener.
     * @param eventListener the event listener to remove
     */
    public void removeEventListener(IMXEventListener eventListener) {
        mDataHandler.removeListener(mEventListeners.get(eventListener));
        mEventListeners.remove(eventListener);
    }

    /**
     * Reset the back state so that future history requests start over from live.
     * Must be called when opening a room if interested in history.
     */
    public void initHistory() {
        mBackState = mLiveState.deepCopy();
        canStillPaginate = true;
    }

    /**
     * Process a state event to keep the internal live and back states up to date.
     * @param event the state event
     * @param direction the direction; ie. forwards for live state, backwards for back state
     */
    public void processStateEvent(Event event, EventDirection direction) {
        RoomState affectedState = (direction == EventDirection.FORWARDS) ? mLiveState : mBackState;
        affectedState.applyState(event, direction);
    }

    /**
     * Process the live state events for the room. Only once this is done is the room considered ready to pass on events.
     * @param stateEvents the state events describing the state of the room
     */
    public void processLiveState(List<Event> stateEvents) {
        for (Event event : stateEvents) {
            processStateEvent(event, EventDirection.FORWARDS);
        }
        isReady = true;
    }

    /**
     * Send a message to the room.
     * @param message the message
     * @param callback the callback with the created event
     */
    public void sendMessage(Message message, ApiCallback<Event> callback) {
        mDataRetriever.getRoomsRestClient().sendMessage(mRoomId, message, callback);
    }

    /**
     * Request older messages. They will come down the onBackEvent callback.
     * @param callback callback to implement to be informed that the pagination request has been completed. Can be null.
     */
    public void requestHistory(final ApiCallback<Integer> callback) {
        if (isPaginating // One at a time please
                || !canStillPaginate // If we have already reached the end of history
                || !isReady) { // If the room is not finished being set up
            return;
        }
        isPaginating = true;
        mDataRetriever.requestRoomHistory(mRoomId, mBackState.getToken(), new SimpleApiCallback<TokensChunkResponse<Event>>(callback) {
            @Override
            public void onSuccess(TokensChunkResponse<Event> response) {
                mBackState.setToken(response.end);
                for (Event event : response.chunk) {
                    if (event.stateKey != null) {
                        processStateEvent(event, EventDirection.BACKWARDS);
                    }
                    mDataHandler.onBackEvent(event, mBackState.deepCopy());
                }
                if (response.chunk.size() == 0) {
                    canStillPaginate = false;
                }
                if (callback != null) {
                    callback.onSuccess(response.chunk.size());
                }
                isPaginating = false;
            }

            @Override
            public void onMatrixError(MatrixError e) {
                // When we've retrieved all the messages from a room, the pagination token is some invalid value
                if (MatrixError.UNKNOWN.equals(e.errcode)) {
                    canStillPaginate = false;
                }
                isPaginating = false;
                super.onMatrixError(e);
            }

            @Override
            public void onNetworkError(Exception e) {
                isPaginating = false;
                super.onNetworkError(e);
            }

            @Override
            public void onUnexpectedError(Exception e) {
                isPaginating = false;
                super.onUnexpectedError(e);
            }
        });
    }

    /**
     * Shorthand for {@link #requestHistory(org.matrix.androidsdk.rest.callback.ApiCallback)} with a null callback.
     */
    public void requestHistory() {
        requestHistory(null);
    }

    /**
     * Join the room. If successful, the room's current state will be loaded before calling back onComplete.
     * @param callback the callback for when done
     */
    public void join(final ApiCallback<Void> callback) {
        mDataRetriever.getRoomsRestClient().joinRoom(mRoomId, new SimpleApiCallback<Void>(callback) {
            @Override
            public void onSuccess(final Void aVoid) {
                // Once we've joined, we run an initial sync on the room to have all of its information
                initialSync(callback);
            }
        });
    }

    /**
     * Perform a room-level initial sync to get latest messages and pagination token.
     * @param callback the async callback
     */
    public void initialSync(final ApiCallback<Void> callback) {
        mDataRetriever.getRoomsRestClient().initialSync(mRoomId, new SimpleApiCallback<RoomResponse>(callback) {
            @Override
            public void onSuccess(RoomResponse roomInfo) {
                mDataHandler.handleInitialRoomResponse(roomInfo, Room.this);
                if (callback != null) {
                    callback.onSuccess(null);
                }
            }
        });
    }

    /**
     * Shorthand for {@link #join(org.matrix.androidsdk.rest.callback.ApiCallback)} with a null callback.
     */
    public void join() {
        join(null);
    }

    /**
     * Invite a user to this room.
     * @param userId the user id
     * @param callback the callback for when done
     */
    public void invite(String userId, ApiCallback<Void> callback) {
        mDataRetriever.getRoomsRestClient().inviteToRoom(mRoomId, userId, callback);
    }

    /**
     * Leave the room.
     * @param callback the callback for when done
     */
    public void leave(ApiCallback<Void> callback) {
        mDataRetriever.getRoomsRestClient().leaveRoom(mRoomId, callback);
    }

    /**
     * Kick a user from the room.
     * @param userId the user id
     * @param callback the async callback
     */
    public void kick(String userId, ApiCallback<Void> callback) {
        mDataRetriever.getRoomsRestClient().kickFromRoom(mRoomId, userId, callback);
    }

    /**
     * Ban a user from the room.
     * @param userId the user id
     * @param callback the async callback
     */
    public void ban(String userId, ApiCallback<Void> callback) {
        BannedUser user = new BannedUser();
        user.userId = userId;
        mDataRetriever.getRoomsRestClient().banFromRoom(mRoomId, user, callback);
    }

    /**
     * Unban a user.
     * @param userId the user id
     * @param callback the async callback
     */
    public void unban(String userId, ApiCallback<Void> callback) {
        // Unbanning is just setting a member's state to left, like kick
        kick(userId, callback);
    }

    /**
     * Update the room's name.
     * @param name the new name
     * @param callback the async callback
     */
    public void updateName(String name, ApiCallback<Void> callback) {
        mDataRetriever.getRoomsRestClient().updateName(getRoomId(), name, callback);
    }

    /**
     * Update the room's topic.
     * @param topic the new topic
     * @param callback the async callback
     */
    public void updateTopic(String topic, ApiCallback<Void> callback) {
        mDataRetriever.getRoomsRestClient().updateTopic(getRoomId(), topic, callback);
    }

    /**
     * Redact an event from the room.
     * @param eventId the event's id
     * @param callback the callback with the created event
     */
    public void redact(String eventId, ApiCallback<Event> callback) {
        mDataRetriever.getRoomsRestClient().redact(getRoomId(), eventId, callback);
    }
}

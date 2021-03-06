package org.matrix.androidsdk.data;

import android.util.Log;

import com.google.gson.JsonObject;

import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.TokensChunkResponse;
import org.matrix.androidsdk.rest.model.User;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory IMXStore.
 */
public class MXMemoryStore implements IMXStore {

    private Map<String, Room> mRooms = new ConcurrentHashMap<String, Room>();
    private Map<String, User> mUsers = new ConcurrentHashMap<String, User>();
    // room id -> map of (event_id -> event) events for this room (linked so insertion order is preserved)
    private Map<String, LinkedHashMap<String, Event>> mRoomEvents = new ConcurrentHashMap<String, LinkedHashMap<String, Event>>();
    private Map<String, String> mRoomTokens = new ConcurrentHashMap<String, String>();

    private Map<String, RoomSummary> mRoomSummaries = new ConcurrentHashMap<String, RoomSummary>();

    @Override
    public Collection<Room> getRooms() {
        return mRooms.values();
    }

    public Collection<User> getUsers() {
        return mUsers.values();
    }

    @Override
    public Room getRoom(String roomId) {
        return mRooms.get(roomId);
    }

    @Override
    public User getUser(String userId) {
        return mUsers.get(userId);
    }

    @Override
    public void storeUser(User user) {
        mUsers.put(user.userId, user);
    }

    @Override
    public void storeRoom(Room room) {
        mRooms.put(room.getRoomId(), room);
    }

    @Override
    public Event getOldestEvent(String roomId) {
        LinkedHashMap<String, Event> events = mRoomEvents.get(roomId);

        if (events != null) {
            Iterator<Event> it = events.values().iterator();
            if (it.hasNext()) {
                return it.next();
            }
        }
        return null;
    }

    @Override
    public void storeLiveRoomEvent(Event event) {
        LinkedHashMap<String, Event> events = mRoomEvents.get(event.roomId);
        if (events != null) {
            // If we don't have any information on this room - a pagination token, namely - we don't store the event but instead
            // wait for the first pagination request to set things right
            events.put(event.eventId, event);
        }
    }

    @Override
    public void storeRoomEvents(String roomId, TokensChunkResponse<Event> eventsResponse, Room.EventDirection direction) {
        if (direction == Room.EventDirection.FORWARDS) { // TODO: Implement backwards direction
            LinkedHashMap<String, Event> events = mRoomEvents.get(roomId);
            if (events == null) {
                events = new LinkedHashMap<String, Event>();
                mRoomEvents.put(roomId, events);
                mRoomTokens.put(roomId, eventsResponse.start);
            }
            for (Event event : eventsResponse.chunk) {
                events.put(event.eventId, event);
            }
        }
    }

    @Override
    public void updateEventContent(String roomId, String eventId, JsonObject newContent) {
        LinkedHashMap<String, Event> events = mRoomEvents.get(roomId);
        if (events != null) {
            Event event = events.get(eventId);
            if (event != null) {
                event.content = newContent;
            }
        }
    }

    @Override
    public void storeSummary(String roomId, Event event, RoomState roomState, String selfUserId) {
        Room room = mRooms.get(roomId);
        if (room != null) { // Should always be the case
            RoomSummary summary = mRoomSummaries.get(roomId);
            if (summary == null) {
                summary = new RoomSummary();
            }
            summary.setLatestEvent(event);
            summary.setLatestRoomState(roomState);
            summary.setMembers(room.getMembers());
            summary.setName(room.getName(selfUserId));
            summary.setRoomId(room.getRoomId());
            summary.setTopic(room.getTopic());

            mRoomSummaries.put(roomId, summary);
        }
    }

    @Override
    public TokensChunkResponse<Event> getRoomEvents(String roomId, String token) {
        // For now, we return everything we have for the original null token request
        // For older requests (providing a token), returning null for now
        if (token == null) {
            LinkedHashMap<String, Event> events = mRoomEvents.get(roomId);
            if (events == null) {
                return null;
            }
            TokensChunkResponse<Event> response = new TokensChunkResponse<Event>();
            response.chunk = new ArrayList<Event>(events.values());
            // We want a chunk that goes from most recent to least
            Collections.reverse(response.chunk);
            response.end = mRoomTokens.get(roomId);
            return response;
        }
        return null;
    }

    @Override
    public Collection<RoomSummary> getSummaries() {
        return mRoomSummaries.values();
    }

    @Override
    public RoomSummary getSummary(String roomId) {
        return mRoomSummaries.get(roomId);
    }
}

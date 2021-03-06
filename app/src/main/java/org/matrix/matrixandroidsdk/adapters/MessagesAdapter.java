package org.matrix.matrixandroidsdk.adapters;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.gson.JsonNull;

import org.matrix.androidsdk.data.MyUser;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.ImageInfo;
import org.matrix.androidsdk.rest.model.ImageMessage;
import org.matrix.androidsdk.rest.model.Message;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.util.ContentManager;
import org.matrix.androidsdk.util.JsonUtils;
import org.matrix.matrixandroidsdk.Matrix;
import org.matrix.matrixandroidsdk.R;
import org.matrix.matrixandroidsdk.util.EventUtils;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * An adapter which can display events. Events are not limited to m.room.message event types, but
 * can include topic changes (m.room.topic) and room member changes (m.room.member).
 */
public class MessagesAdapter extends ArrayAdapter<MessageRow> {

    // text, images, notices(topics, room names, membership changes,
    // displayname changes, avatar url changes), and emotes!
    private static final int NUM_ROW_TYPES = 4;

    private static final int ROW_TYPE_TEXT = 0;
    private static final int ROW_TYPE_IMAGE = 1;
    private static final int ROW_TYPE_NOTICE = 2;
    private static final int ROW_TYPE_EMOTE = 3;

    private static final int MAX_IMAGE_WIDTH = 320;

    private static final String LOG_TAG = "MessagesAdapter";

    private Context mContext;
    private HashMap<Integer, Integer> mRowTypeToLayoutId = new HashMap<Integer, Integer>();
    private LayoutInflater mLayoutInflater;

    // To keep track of events and avoid duplicates. For instance, we add a message event
    // when the current user sends one but it will also come down the event stream
    private HashMap<String, MessageRow> mEventRowMap = new HashMap<String, MessageRow>();

    private int mOddColourResId;
    private int mEvenColourResId;

    private int normalColor;
    private int emoteColor;
    private int notSentColor;
    private int sendingColor;
    private int highlightColor;

    private static boolean mDisplayMessageTimestamp = false;

    private DateFormat mDateFormat;

    public MessagesAdapter(Context context, int textResLayoutId, int imageResLayoutId,
                           int noticeResLayoutId, int emoteRestLayoutId) {
        super(context, 0);
        mContext = context;
        mRowTypeToLayoutId.put(ROW_TYPE_TEXT, textResLayoutId);
        mRowTypeToLayoutId.put(ROW_TYPE_IMAGE, imageResLayoutId);
        mRowTypeToLayoutId.put(ROW_TYPE_NOTICE, noticeResLayoutId);
        mRowTypeToLayoutId.put(ROW_TYPE_EMOTE, emoteRestLayoutId);
        mLayoutInflater = LayoutInflater.from(mContext);
        mDateFormat = new SimpleDateFormat("MMM d HH:mm", Locale.getDefault());
        // the refresh will be triggered only when it is required
        // for example, retrieve the historical messages triggers a refresh for each message
        setNotifyOnChange(false);

        normalColor = context.getResources().getColor(R.color.message_normal);
        emoteColor = context.getResources().getColor(R.color.message_emote);
        notSentColor = context.getResources().getColor(R.color.message_not_sent);
        sendingColor = context.getResources().getColor(R.color.message_sending);
        highlightColor = context.getResources().getColor(R.color.message_highlighted);
    }

    public void setAlternatingColours(int oddResId, int evenResId) {
        mOddColourResId = oddResId;
        mEvenColourResId = evenResId;
    }

    @Override
    public int getViewTypeCount() {
        return NUM_ROW_TYPES;
    }

    public void addToFront(Event event, RoomState roomState) {
        MessageRow row = new MessageRow(event, roomState);
        if (shouldSave(row)) {
            insert(row, 0);
            if (row.getEvent().eventId != null) {
                mEventRowMap.put(row.getEvent().eventId, row);
            }
        }
    }

    public void add(Event event, RoomState roomState) {
        add(new MessageRow(event, roomState));
    }

    @Override
    public void add(MessageRow row) {
        if (shouldSave(row)) {
            super.add(row);
            if (row.getEvent().eventId != null) {
                mEventRowMap.put(row.getEvent().eventId, row);
            }
            this.notifyDataSetChanged();
        }
    }

    public void removeEventById(String eventId) {
        MessageRow row = mEventRowMap.get(eventId);
        if (row != null) {
            remove(row);
        }
    }

    private boolean shouldSave(MessageRow row) {
        return (isDisplayableEvent(row.getEvent(), row.getRoomState()) && !mEventRowMap.containsKey(row.getEvent().eventId));
    }

    @Override
    public int getItemViewType(int position) {
        MessageRow row = getItem(position);
        Event event = row.getEvent();

        if (Event.EVENT_TYPE_MESSAGE.equals(event.type)) {
            Message message = JsonUtils.toMessage(event.content);

            if (Message.MSGTYPE_TEXT.equals(message.msgtype)) {
                return ROW_TYPE_TEXT;
            }
            else if (Message.MSGTYPE_IMAGE.equals(message.msgtype)) {
                return ROW_TYPE_IMAGE;
            }
            else if (Message.MSGTYPE_EMOTE.equals(message.msgtype)) {
                return ROW_TYPE_EMOTE;
            }
            else {
                // Default is to display the body as text
                return ROW_TYPE_TEXT;
            }
        }
        else if (Event.EVENT_TYPE_STATE_ROOM_TOPIC.equals(event.type) ||
                 Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(event.type) ||
                 Event.EVENT_TYPE_STATE_ROOM_NAME.equals(event.type)) {
            return ROW_TYPE_NOTICE;
        }
        else {
            throw new RuntimeException("Unknown event type: " + event.type);
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

        try {
            switch (getItemViewType(position)) {
                case ROW_TYPE_TEXT:
                    return getTextView(position, convertView, parent);
                case ROW_TYPE_IMAGE:
                    return getImageView(position, convertView, parent);
                case ROW_TYPE_NOTICE:
                    return getNoticeView(position, convertView, parent);
                case ROW_TYPE_EMOTE:
                    return getEmoteView(position, convertView, parent);
                default:
                    throw new RuntimeException("Unknown item view type for position " + position);
            }
        }
        catch (Exception e) {
            Log.e(LOG_TAG, "Failed to render view at position " + position + ": "+e);
            return convertView;
        }
    }

    private String getUserDisplayName(String userId, RoomState roomState) {
        RoomMember roomMember = roomState.getMember(userId);
        return (roomMember != null) ? roomMember.getName() : userId;
    }

    // return true if convertView is merged with previous View
    private boolean manageSubView(int position, View convertView, View subView) {

        MessageRow row = getItem(position);
        Event msg = row.getEvent();
        RoomState roomState = row.getRoomState();

        MyUser myUser = Matrix.getInstance(mContext).getDefaultSession().getMyUser();
        Boolean isMyEvent = myUser.userId.equals(msg.userId);

        //
        String prevUserId = null;
        if (position > 0) {
            MessageRow prevRow = getItem(position-1);

            if (null != prevRow) {
                prevUserId = prevRow.getEvent().userId;
            }
        }

        String nextUserId = null;

        if ((position+1) < this.getCount()) {
            MessageRow nextRow = getItem(position+1);

            if (null != nextRow) {
                nextUserId = nextRow.getEvent().userId;
            }
        }

        // isMergedView -> the message is going to be merged with the previous one
        // willBeMerged -> false if it is the last message of the user
        boolean isMergedView = (null != prevUserId) && (prevUserId.equals(msg.userId));
        boolean willBeMerged = (null != nextUserId) && (nextUserId.equals(msg.userId));

        // manage sender text
        TextView textView = (TextView) convertView.findViewById(R.id.messagesAdapter_sender);
        if (null != textView) {
            if (isMergedView || isMyEvent) {
                textView.setVisibility(View.GONE);
            } else {
                textView.setVisibility(View.VISIBLE);
                textView.setText(getUserDisplayName(msg.userId, row.getRoomState()));
            }
        }

        TextView leftTsTextView = (TextView) convertView.findViewById(R.id.messagesAdapter_timestamp_left);
        TextView rightTsTextView = (TextView) convertView.findViewById(R.id.messagesAdapter_timestamp_right);
        TextView tsTextView;

        if (isMyEvent) {
            tsTextView = leftTsTextView;
            rightTsTextView.setVisibility(View.GONE);
        } else {
            leftTsTextView.setVisibility(View.GONE);
            tsTextView = rightTsTextView;
        }

        tsTextView.setVisibility(View.VISIBLE);
        tsTextView.setText(mDisplayMessageTimestamp ? getTimestamp(msg.originServerTs) : "            ");

        tsTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MessagesAdapter.this.mDisplayMessageTimestamp = !MessagesAdapter.this.mDisplayMessageTimestamp;
                MessagesAdapter.this.notifyDataSetChanged();
            }
        });

        // Sender avatar
        RoomMember sender = roomState.getMember(msg.userId);

        View avatarLeftView = convertView.findViewById(R.id.messagesAdapter_roundAvatar_left);
        View avatarRightView = convertView.findViewById(R.id.messagesAdapter_roundAvatar_right);
        View avatarLayoutView;

        if (isMyEvent) {
            avatarLayoutView = avatarRightView;
            avatarLeftView.setVisibility(View.GONE);

        } else {
            avatarLayoutView = avatarLeftView;
            avatarRightView.setVisibility(View.GONE);
        }

        ImageView avatarImageView = (ImageView) avatarLayoutView.findViewById(R.id.avatar_img);

        if (isMergedView) {
            avatarLayoutView.setVisibility(View.GONE);
        } else {
            avatarLayoutView.setVisibility(View.VISIBLE);
            avatarImageView.setTag(null);
            avatarImageView.setImageResource(R.drawable.ic_contact_picture_holo_light);

            String url = null;

            if (sender != null) {
                url = sender.avatarUrl;
            } else {
                // join event
                // check if the avatar_url is defined in the event body
                // roomState is updated after managing this event
                // so, this user could miss
                if (msg.content.has("avatar_url")) {
                    url = msg.content.get("avatar_url") == JsonNull.INSTANCE ? null : msg.content.get("avatar_url").getAsString();
                }
            }

            if (!TextUtils.isEmpty(url)) {
                loadAvatar(avatarImageView, url);
            }
        }

        // if the messages are merged
        // the thumbnail is hidden
        // and the subview must be moved to be aligned with the previous body
        View bodyLayoutView = convertView.findViewById(R.id.messagesAdapter_body_layout);
        ViewGroup.MarginLayoutParams bodyLayout = (ViewGroup.MarginLayoutParams) bodyLayoutView.getLayoutParams();
        FrameLayout.LayoutParams subViewLinearLayout = (FrameLayout.LayoutParams) subView.getLayoutParams();

        View view = convertView.findViewById(R.id.messagesAdapter_roundAvatar_left);
        ViewGroup.LayoutParams avatarLayout = view.getLayoutParams();

        if (!isMyEvent) {
            subViewLinearLayout.gravity =  Gravity.LEFT | Gravity.CENTER_VERTICAL;

            if (isMergedView) {
                bodyLayout.setMargins(avatarLayout.width, bodyLayout.topMargin, 4, bodyLayout.bottomMargin);

            } else {
                bodyLayout.setMargins(4, bodyLayout.topMargin, 4, bodyLayout.bottomMargin);
            }
            subView.setLayoutParams(bodyLayout);
        } else {
            subViewLinearLayout.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
            
            if (isMergedView) {
                bodyLayout.setMargins(4, bodyLayout.topMargin, avatarLayout.width, bodyLayout.bottomMargin);
            } else {
                bodyLayout.setMargins(4, bodyLayout.topMargin, 4, bodyLayout.bottomMargin);
            }
        }

        bodyLayoutView.setLayoutParams(bodyLayout);
        subView.setLayoutParams(subViewLinearLayout);

        view = convertView.findViewById(R.id.messagesAdapter_message_separator);

        if (null != view) {
            view.setVisibility((willBeMerged || ((position+1) == this.getCount())) ? View.GONE : View.VISIBLE);
        }

        return isMergedView;
    }

    private View getTextView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = mLayoutInflater.inflate(mRowTypeToLayoutId.get(ROW_TYPE_TEXT), parent, false);
        }

        MessageRow row = getItem(position);
        Event msg = row.getEvent();
        RoomState roomState = row.getRoomState();

        AdapterUtils.EventDisplay display = new AdapterUtils.EventDisplay(mContext, msg, roomState);
        final CharSequence body = display.getTextualDisplay();
        final TextView bodyTextView = (TextView) convertView.findViewById(R.id.messagesAdapter_body);
        bodyTextView.setText(body);

        int textColor;
        switch (row.getSentState()) {
            case SENDING:
                textColor = sendingColor;
                break;
            case NOT_SENT:
                textColor = notSentColor;
                break;
            default:
                textColor = EventUtils.shouldHighlight(mContext, msg) ? highlightColor : normalColor;
        }
        bodyTextView.setTextColor(textColor);

        this.manageSubView(position, convertView, bodyTextView);

        setBackgroundColour(convertView, position);
        return convertView;
    }

    private View getImageView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = mLayoutInflater.inflate(mRowTypeToLayoutId.get(ROW_TYPE_IMAGE), parent, false);
        }

        MessageRow row = getItem(position);
        Event msg = row.getEvent();

        final ImageMessage imageMessage = JsonUtils.toImageMessage(msg.content);

        String thumbUrl = null;
        ImageInfo imageInfo = null;
        if (imageMessage != null) {
            // Backwards compatibility with events from before Synapse 0.6.0
            if (imageMessage.thumbnailUrl != null) {
                thumbUrl = imageMessage.thumbnailUrl;
            } else if (imageMessage.url != null) {
                thumbUrl = imageMessage.url;
                imageInfo = imageMessage.info;
            }
        }

        ImageView imageView = (ImageView) convertView.findViewById(R.id.messagesAdapter_image);

        if (imageInfo != null) {
            // Bound the width of the thumbnail
            int w, h;
            if (imageInfo.w > MAX_IMAGE_WIDTH) {
                w = MAX_IMAGE_WIDTH;
                h = imageInfo.h * MAX_IMAGE_WIDTH / imageInfo.w;
            }
            else {
                w = imageInfo.w;
                h = imageInfo.h;
            }
            AdapterUtils.loadThumbnailBitmap(imageView, thumbUrl, w, h);
        }
        else {
            AdapterUtils.loadBitmap(imageView, thumbUrl);
        }

        if ((imageMessage != null) && (imageMessage.url != null)) {
            imageView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent viewImageIntent = new Intent();
                    viewImageIntent.setAction(Intent.ACTION_VIEW);
                    String type = ((imageMessage.info != null) && (imageMessage.info.mimetype != null)) ? imageMessage.info.mimetype : "image/*";
                    ContentManager contentManager = Matrix.getInstance(getContext()).getDefaultSession().getContentManager();
                    String downloadableUrl = contentManager.getDownloadableUrl(imageMessage.url);
                    viewImageIntent.setDataAndType(Uri.parse(downloadableUrl), type);
                    mContext.startActivity(viewImageIntent);
                }
            });
        }

        this.manageSubView(position, convertView, imageView);

        setBackgroundColour(convertView, position);
        return convertView;
    }

    private View getNoticeView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = mLayoutInflater.inflate(mRowTypeToLayoutId.get(ROW_TYPE_NOTICE), parent, false);
        }

        MessageRow row = getItem(position);
        Event msg = row.getEvent();
        RoomState roomState = row.getRoomState();

        AdapterUtils.EventDisplay display = new AdapterUtils.EventDisplay(mContext, msg, roomState);
        CharSequence notice = display.getTextualDisplay();

        TextView noticeTextView = (TextView) convertView.findViewById(R.id.messagesAdapter_notice);
        noticeTextView.setText(notice);

        this.manageSubView(position, convertView, noticeTextView);

        return convertView;
    }

    private void loadAvatar(ImageView avatarView, String url) {
        int size = getContext().getResources().getDimensionPixelSize(R.dimen.chat_avatar_size);
        AdapterUtils.loadThumbnailBitmap(avatarView, url, size, size);
    }

    private View getEmoteView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = mLayoutInflater.inflate(mRowTypeToLayoutId.get(ROW_TYPE_EMOTE), parent, false);
        }

        MessageRow row = getItem(position);
        Event msg = row.getEvent();
        RoomState roomState = row.getRoomState();

        String emote = getUserDisplayName(msg.userId, roomState) + " ";

        AdapterUtils.EventDisplay display = new AdapterUtils.EventDisplay(mContext, msg, roomState);
        emote += display.getTextualDisplay();

        TextView emoteTextView = (TextView) convertView.findViewById(R.id.messagesAdapter_emote);
        emoteTextView.setText(emote);

        int textColor;
        switch (row.getSentState()) {
            case SENDING:
                textColor = sendingColor;
                break;
            case NOT_SENT:
                textColor = notSentColor;
                break;
            default:
                textColor = emoteColor;
        }
        emoteTextView.setTextColor(textColor);

        this.manageSubView(position, convertView, emoteTextView);

        return convertView;
    }

    private String getTimestamp(long ts) {
        return mDateFormat.format(new Date(ts));
    }

    private void setBackgroundColour(View view, int position) {
        if (mOddColourResId != 0 && mEvenColourResId != 0) {
            view.setBackgroundColor(position%2 == 0 ? mEvenColourResId : mOddColourResId);
        }
    }

    private boolean isDisplayableEvent(Event event, RoomState roomState) {
        if (Event.EVENT_TYPE_MESSAGE.equals(event.type)) {
            // A message is displayable as long as it has a body
            Message message = JsonUtils.toMessage(event.content);
            return (message.body != null) && (!message.body.equals(""));
        }
        else if (Event.EVENT_TYPE_STATE_ROOM_TOPIC.equals(event.type)
                || Event.EVENT_TYPE_STATE_ROOM_NAME.equals(event.type)) {
            return true;
        }
        else if (Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(event.type)) {
            // if we can display text for it, it's valid.
            AdapterUtils.EventDisplay display = new AdapterUtils.EventDisplay(mContext, event, roomState);
            return display.getTextualDisplay() != null;
        }
        return false;
    }


}

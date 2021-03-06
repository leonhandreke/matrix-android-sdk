package org.matrix.matrixandroidsdk.activity;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.model.ContentResponse;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.ImageMessage;
import org.matrix.androidsdk.util.ContentManager;
import org.matrix.androidsdk.util.ContentUtils;
import org.matrix.androidsdk.util.JsonUtils;
import org.matrix.matrixandroidsdk.ErrorListener;
import org.matrix.matrixandroidsdk.Matrix;
import org.matrix.matrixandroidsdk.MyPresenceManager;
import org.matrix.matrixandroidsdk.R;
import org.matrix.matrixandroidsdk.ViewedRoomTracker;
import org.matrix.matrixandroidsdk.fragments.MatrixMessageListFragment;
import org.matrix.matrixandroidsdk.fragments.RoomMembersDialogFragment;
import org.matrix.matrixandroidsdk.util.ResourceUtils;


/**
 * Displays a single room with messages.
 */
public class RoomActivity extends MXCActionBarActivity {

    public static final String EXTRA_ROOM_ID = "org.matrix.matrixandroidsdk.RoomActivity.EXTRA_ROOM_ID";

    private static final String TAG_FRAGMENT_MATRIX_MESSAGE_LIST = "org.matrix.androidsdk.RoomActivity.TAG_FRAGMENT_MATRIX_MESSAGE_LIST";
    private static final String TAG_FRAGMENT_MEMBERS_DIALOG = "org.matrix.androidsdk.RoomActivity.TAG_FRAGMENT_MEMBERS_DIALOG";
    private static final String LOG_TAG = "RoomActivity";

    private static final int REQUEST_IMAGE = 0;

    private MatrixMessageListFragment mMatrixMessageListFragment;
    private MXSession mSession;
    private Room mRoom;

    private MXEventListener mEventListener = new MXEventListener() {
        @Override
        public void onLiveEvent(final Event event, RoomState roomState) {
            RoomActivity.this.runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    // The various events that could possibly change the room title
                    if (Event.EVENT_TYPE_STATE_ROOM_NAME.equals(event.type)
                            || Event.EVENT_TYPE_STATE_ROOM_ALIASES.equals(event.type)
                            || Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(event.type)) {
                        setTitle(mRoom.getName(mSession.getCredentials().userId));
                    }
                    else if (Event.EVENT_TYPE_STATE_ROOM_TOPIC.equals(event.type)) {
                        Log.e(LOG_TAG, "Updating room topic.");
                        RoomState roomState = JsonUtils.toRoomState(event.content);
                        setTopic(roomState.topic);
                    }
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        Intent intent = getIntent();
        if (!intent.hasExtra(EXTRA_ROOM_ID)) {
            Log.e(LOG_TAG, "No room ID extra.");
            finish();
            return;
        }
        String roomId = intent.getStringExtra(EXTRA_ROOM_ID);
        Log.i(LOG_TAG, "Displaying "+roomId);

        findViewById(R.id.button_send).setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                EditText editText = (EditText)findViewById(R.id.editText_messageBox);
                String body = editText.getText().toString();
                sendMessage(body);
                editText.setText("");
            }
        });

        findViewById(R.id.button_more).setOnClickListener(new View.OnClickListener() {
            private static final int OPTION_CANCEL = 0;
            private static final int OPTION_ATTACH_IMAGE = 1;
            private static final int OPTION_INVITE = 2;

            @Override
            public void onClick(View v) {
                final int[] options = new int[] {OPTION_ATTACH_IMAGE, OPTION_INVITE, OPTION_CANCEL};

                new AlertDialog.Builder(RoomActivity.this)
                        .setItems(buildOptionLabels(options), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                switch (options[which]) {
                                    case OPTION_CANCEL:
                                        dialog.cancel();
                                        break;
                                    case OPTION_ATTACH_IMAGE:
                                        Intent fileIntent = new Intent(Intent.ACTION_PICK);
                                        fileIntent.setType("image/*");
                                        startActivityForResult(fileIntent, REQUEST_IMAGE);
                                        break;
                                    case OPTION_INVITE: {
                                            final MXSession session = Matrix.getInstance(getApplicationContext()).getDefaultSession();
                                            if (session != null) {
                                                AlertDialog alert = CommonActivityUtils.createEditTextAlert(RoomActivity.this, "Invite User", "@localpart:domain", new CommonActivityUtils.OnSubmitListener() {
                                                    @Override
                                                    public void onSubmit(final String text) {
                                                        if (TextUtils.isEmpty(text)) {
                                                            return;
                                                        }
                                                        if (!text.startsWith("@") || !text.contains(":")) {
                                                            Toast.makeText(getApplicationContext(), "User must be of the form '@name:example.com'.", Toast.LENGTH_LONG).show();
                                                            return;
                                                        }
                                                        mRoom.invite(text.trim(), new SimpleApiCallback<Void>() {
                                                            @Override
                                                            public void onSuccess(Void info) {
                                                                Toast.makeText(getApplicationContext(), "Sent invite to " + text.trim() + ".", Toast.LENGTH_LONG).show();
                                                            }
                                                        });
                                                    }

                                                    @Override
                                                    public void onCancelled() {

                                                    }
                                                });
                                                alert.show();
                                            }
                                        }

                                        break;
                                }
                            }
                        })
                        .show();
            }

            private String[] buildOptionLabels(int[] options) {
                String[] labels = new String[options.length];
                for (int i = 0; i < options.length; i++) {
                    String label = "";
                    switch (options[i]) {
                        case OPTION_CANCEL:
                            label = getString(R.string.cancel);
                            break;
                        case OPTION_ATTACH_IMAGE:
                            label = getString(R.string.option_attach_image);
                            break;
                        case OPTION_INVITE:
                            label = getString(R.string.option_invite);
                            break;
                    }
                    labels[i] = label;
                }

                return labels;
            }
        });


        // make sure we're logged in.
        mSession = Matrix.getInstance(getApplicationContext()).getDefaultSession();
        if (mSession == null) {
            Log.e(LOG_TAG, "No MXSession.");
            finish();
            return;
        }

        mRoom = mSession.getDataHandler().getRoom(roomId);

        FragmentManager fm = getSupportFragmentManager();
        mMatrixMessageListFragment = (MatrixMessageListFragment) fm.findFragmentByTag(TAG_FRAGMENT_MATRIX_MESSAGE_LIST);

        if (mMatrixMessageListFragment == null) {
            // this fragment displays messages and handles all message logic
            mMatrixMessageListFragment = MatrixMessageListFragment.newInstance(mRoom.getRoomId());
            fm.beginTransaction().add(R.id.anchor_fragment_messages, mMatrixMessageListFragment, TAG_FRAGMENT_MATRIX_MESSAGE_LIST).commit();
        }

        // set general room information
        setTitle(mRoom.getName(mSession.getCredentials().userId));
        setTopic(mRoom.getTopic());

        // listen for room name or topic changes
        mRoom.addEventListener(mEventListener);

        // The error listener needs the current activity
        mSession.setFailureCallback(new ErrorListener(this));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mRoom.removeEventListener(mEventListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ViewedRoomTracker.getInstance().setViewedRoomId(null);
        MyPresenceManager.getInstance(this).advertiseUnavailableAfterDelay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ViewedRoomTracker.getInstance().setViewedRoomId(mRoom.getRoomId());
        MyPresenceManager.getInstance(this).advertiseOnline();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.room, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (CommonActivityUtils.handleMenuItemSelected(this, id)) {
            return true;
        }

        if (id == R.id.action_leave) {
            MXSession session = Matrix.getInstance(getApplicationContext()).getDefaultSession();
            if (session != null) {
                mRoom.leave(new SimpleApiCallback<Void>() {

                    @Override
                    public void onSuccess(Void info) {
                        RoomActivity.this.finish();
                    }
                });
            }
        }
        else if (id == R.id.action_members) {
            FragmentManager fm = getSupportFragmentManager();

            RoomMembersDialogFragment fragment = (RoomMembersDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_MEMBERS_DIALOG);
            if (fragment != null) {
                fragment.dismissAllowingStateLoss();
            }
            fragment = RoomMembersDialogFragment.newInstance(mRoom.getRoomId());
            fragment.show(fm, TAG_FRAGMENT_MEMBERS_DIALOG);
        }
        else if (id == R.id.action_info) {
            Intent startRoomInfoIntent = new Intent(this, RoomInfoActivity.class);
            startRoomInfoIntent.putExtra(EXTRA_ROOM_ID, mRoom.getRoomId());
            startActivity(startRoomInfoIntent);
        }
        return super.onOptionsItemSelected(item);
    }

    private void setTopic(String topic) {
        this.getActionBar().setSubtitle(topic);
    }

    private void sendMessage(String body) {
        if (!TextUtils.isEmpty(body)) {
            if (body.length() > 4 && (body.toLowerCase().startsWith("/me ") || body.toLowerCase().startsWith("/em "))) {
                mMatrixMessageListFragment.sendEmote(body.substring(4));
            }
            else {
                mMatrixMessageListFragment.sendTextMessage(body);
            }
        }

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {
            if (requestCode == REQUEST_IMAGE) {
                Uri selectedImageUri = data.getData();
                final String selectedPath = ResourceUtils.getImagePath(this, selectedImageUri);
                Log.d(LOG_TAG, "Selected image to upload: " + selectedPath);

                final ProgressDialog progressDialog = ProgressDialog.show(this, null, getString(R.string.message_uploading), true);

                mSession.getContentManager().uploadContent(selectedPath, new ContentManager.UploadCallback() {
                    @Override
                    public void onUploadComplete(ContentResponse uploadResponse) {
                        if (uploadResponse == null) {
                            Toast.makeText(RoomActivity.this, "Failed to upload", Toast.LENGTH_LONG).show();
                        }
                        else {
                            Log.d(LOG_TAG, "Uploaded to " + uploadResponse.contentUri);
                            // Build the image message
                            ImageMessage message = new ImageMessage();
                            message.url = uploadResponse.contentUri;
                            message.body = selectedPath.substring(selectedPath.lastIndexOf('/') + 1);

                            message.info = ContentUtils.getImageInfoFromFile(selectedPath);

                            mMatrixMessageListFragment.sendImage(message);
                        }
                        progressDialog.dismiss();
                    }
                });
            }
        }
    }
}

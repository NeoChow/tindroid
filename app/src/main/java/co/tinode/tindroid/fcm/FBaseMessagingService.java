package co.tinode.tindroid.fcm;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

import co.tinode.tindroid.ChatsActivity;
import co.tinode.tindroid.MessageActivity;
import co.tinode.tindroid.R;
import co.tinode.tindroid.UiUtils;
import co.tinode.tindroid.db.BaseDb;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tindroid.widgets.RoundImageDrawable;
import co.tinode.tinodesdk.Storage;
import co.tinode.tinodesdk.Topic;
import co.tinode.tinodesdk.User;

/**
 * Receive and handle (e.g. show) a push notification message.
 */
public class FBaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FBaseMessagingService";

    // Width and height of the large icon (avatar)
    private static final int AVATAR_SIZE = 128;

    private static Bitmap makeLargeIcon(Resources res, Bitmap bmp) {
        if (bmp != null) {
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, AVATAR_SIZE, AVATAR_SIZE, false);
            return new RoundImageDrawable(res, scaled).getRoundedBitmap();
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onMessageReceived(RemoteMessage remoteMessage) {
        // There are two types of messages data messages and notification messages. Data messages are handled
        // here in onMessageReceived whether the app is in the foreground or background. Data messages are the type
        // traditionally used with GCM. Notification messages are only received here in onMessageReceived when the app
        // is in the foreground. When the app is in the background an automatically generated notification is displayed.
        // When the user taps on the notification they are returned to the app. Messages containing both notification
        // and data payloads are treated as notification messages. The Firebase console always sends notification
        // messages. For more see: https://firebase.google.com/docs/cloud-messaging/concept-options

        // Not getting messages here? See why this may be: https://goo.gl/39bRNJ
        Log.d(TAG, "From: " + remoteMessage.getFrom());

        String title = null;
        String body = null;
        String topicName = null;
        Bitmap avatar = null;

        // Check if message contains a data payload.
        if (remoteMessage.getData().size() > 0) {
            Map<String, String> data = remoteMessage.getData();
            Log.d(TAG, "FCM data payload: " + data);

            topicName = data.get("topic");
            if (topicName == null) {
                Log.w(TAG, "NULL topic in a push notification");
                return;
            }

            String visibleTopic = UiUtils.getVisibleTopic();
            if (visibleTopic != null && visibleTopic.equals(topicName)) {
                // No need to display a notification if we are in the topic already.
                Log.d(TAG, "Topic is visible, no need to show a notification");
                return;
            }

            // Fetch locally stored contacts
            Storage store = BaseDb.getInstance().getStore();
            User<VxCard> sender = (User<VxCard>) store.userGet(data.get("xfrom"));
            String senderName  = (sender == null || sender.pub == null) ?
                    getResources().getString(R.string.sender_unknown) : sender.pub.fn;
            Bitmap senderIcon = (sender == null || sender.pub == null) ?
                    null : makeLargeIcon(getResources(), sender.pub.getBitmap());
            Topic.TopicType tp = Topic.getTopicTypeByName(topicName);
            if (tp == Topic.TopicType.P2P) {
                // P2P message
                title = senderName;
                body = data.get("content");
                avatar = senderIcon;

            } else if (tp == Topic.TopicType.GRP) {
                // Group message

                Topic<VxCard,?,?,?> topic = (Topic<VxCard,?,?,?>) store.topicGet(null, topicName);
                if (topic == null) {
                    Log.w(TAG, "Unknown topic: " + topicName);
                    return;
                }

                if (topic.getPub() != null) {
                    title = topic.getPub().fn;
                    body = senderName + ": " + data.get("content");
                    avatar = senderIcon;
                }
            } else {
                Log.w(TAG, "Unexpected topic type=" + tp);
                return;
            }
        } else if (remoteMessage.getNotification() != null) {
            RemoteMessage.Notification data = remoteMessage.getNotification();
            Log.d(TAG, "MessageDb Notification Body: " + data.getBody());

            topicName = null;
            title = data.getTitle();
            body = data.getBody();
        }

        showNotification(title, body, avatar, topicName);
    }

    /**
     * Create and show a simple notification containing the received FCM message.
     *
     * @param title message title.
     * @param body  message body.
     * @param topic topic handle for action
     */
    private void showNotification(String title, String body, Bitmap avatar, String topic) {
        // Log.d(TAG, "Notification title=" + title + ", body=" + body + ", topic=" + topic);

        Intent intent;
        if (TextUtils.isEmpty(topic)) {
            // Communication on an unknown topic
            intent = new Intent(this, ChatsActivity.class);
        } else {
            // Communication on a known topic
            intent = new Intent(this, MessageActivity.class);
            intent.putExtra("topic", topic);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0 /* Request code */, intent,
                PendingIntent.FLAG_ONE_SHOT);

        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        int icon = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) ?
                R.drawable.ic_logo_push : R.mipmap.ic_launcher;

        int background = ContextCompat.getColor(this, R.color.colorNotificationBackground);

        @SuppressWarnings("deprecation") NotificationCompat.Builder notificationBuilder =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        new NotificationCompat.Builder(this, "new_message") :
                        new NotificationCompat.Builder(this);

        notificationBuilder
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setSmallIcon(icon)
                .setLargeIcon(avatar)
                .setColor(background)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setContentIntent(pendingIntent);

        // MessageActivity will cancel all notifications by tag, which is just topic name.
        // All notifications receive the same id 0 because id is not used.
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(topic, 0, notificationBuilder.build());
        }
    }

    @Override
    public void onNewToken(final String refreshedToken) {
        super.onNewToken(refreshedToken);
        Log.d(TAG, "Refreshed token: " + refreshedToken);

        // Send token to the server.
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        Intent intent = new Intent("FCM_REFRESH_TOKEN");
        intent.putExtra("token", refreshedToken);
        lbm.sendBroadcast(intent);

        // The token is currently retrieved in co.tinode.tindroid.Cache.
    }
}

package app.formstr.calendar;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Background worker that periodically queries Nostr relays for new kind 1052
 * invitation events (gift wraps) addressed to the logged-in user.
 * It only counts events — no decryption is performed.
 */
public class InvitationWorker extends Worker {

    private static final String TAG = "InvitationWorker";
    private static final String PREFS_NAME = "CapacitorStorage";
    private static final String PUBKEY_KEY = "bg:userPubkey";
    private static final String RELAYS_KEY = "bg:relays";
    private static final String LAST_INVITATION_FETCH_KEY = "bg:lastInvitationFetchTime";
    private static final String CHANNEL_ID = "calendar_invitations";
    private static final int NOTIFICATION_ID = 0x1052;
    private static final int MAX_RELAYS = 3;
    private static final long RELAY_TIMEOUT_SECONDS = 15;

    public InvitationWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "InvitationWorker starting");

        try {
            SharedPreferences prefs = getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            String pubkey = parseJsonString(prefs.getString(PUBKEY_KEY, null));
            String relaysRaw = prefs.getString(RELAYS_KEY, null);
            String lastFetchRaw = prefs.getString(LAST_INVITATION_FETCH_KEY, null);

            if (pubkey == null || pubkey.isEmpty()) {
                Log.d(TAG, "No pubkey found, skipping");
                return Result.success();
            }

            if (relaysRaw == null || relaysRaw.isEmpty()) {
                Log.d(TAG, "No relays found, skipping");
                return Result.success();
            }

            // Default to one week ago if no fetch time is stored
            long since = System.currentTimeMillis() / 1000 - 7 * 24 * 60 * 60;
            if (lastFetchRaw != null) {
                try {
                    since = Long.parseLong(lastFetchRaw.replace("\"", ""));
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Failed to parse lastInvitationFetchTime", e);
                }
            }

            JSONArray relaysArray = new JSONArray(relaysRaw);
            int relayCount = Math.min(relaysArray.length(), MAX_RELAYS);

            Set<String> eventIds = new HashSet<>();
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(RELAY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(RELAY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build();

            for (int i = 0; i < relayCount; i++) {
                String relay = relaysArray.getString(i).replace("\"", "");
                queryRelay(client, relay, pubkey, since, eventIds);
            }

            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();

            Log.d(TAG, "Found " + eventIds.size() + " new invitation(s)");

            if (!eventIds.isEmpty()) {
                showNotification(eventIds.size());
            }

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "InvitationWorker failed", e);
            return Result.retry();
        }
    }

    /**
     * Capacitor Preferences stores values as JSON strings (e.g. "\"abc\"").
     * Strip the outer quotes to get the raw value.
     */
    private String parseJsonString(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void queryRelay(OkHttpClient client, String relayUrl, String pubkey,
                            long since, Set<String> eventIds) {
        // Convert wss:// to https:// for OkHttp WebSocket
        String httpUrl = relayUrl.replace("wss://", "https://").replace("ws://", "http://");

        CountDownLatch latch = new CountDownLatch(1);

        try {
            Request request = new Request.Builder().url(httpUrl).build();

            // Build the Nostr REQ filter for kind 1052 events tagged to this pubkey
            String subscriptionId = "inv_" + System.currentTimeMillis();
            JSONArray filter = new JSONArray();
            JSONObject filterObj = new JSONObject();
            filterObj.put("kinds", new JSONArray().put(1052));
            filterObj.put("#p", new JSONArray().put(pubkey));
            if (since > 0) {
                filterObj.put("since", since);
            }
            filter.put(filterObj);

            JSONArray reqMessage = new JSONArray();
            reqMessage.put("REQ");
            reqMessage.put(subscriptionId);
            reqMessage.put(filterObj);

            client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                    webSocket.send(reqMessage.toString());
                }

                @Override
                public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                    try {
                        JSONArray msg = new JSONArray(text);
                        String type = msg.getString(0);

                        if ("EVENT".equals(type) && msg.length() >= 3) {
                            JSONObject event = msg.getJSONObject(2);
                            String id = event.getString("id");
                            synchronized (eventIds) {
                                eventIds.add(id);
                            }
                        } else if ("EOSE".equals(type)) {
                            // Close the subscription and connection
                            JSONArray closeMsg = new JSONArray();
                            closeMsg.put("CLOSE");
                            closeMsg.put(subscriptionId);
                            webSocket.send(closeMsg.toString());
                            webSocket.close(1000, "done");
                            latch.countDown();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to parse relay message", e);
                    }
                }

                @Override
                public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t,
                                      okhttp3.Response response) {
                    Log.w(TAG, "WebSocket failure for " + relayUrl, t);
                    latch.countDown();
                }

                @Override
                public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                    latch.countDown();
                }
            });

            latch.await(RELAY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "Failed to query relay: " + relayUrl, e);
        }
    }

    private void showNotification(int count) {
        Context context = getApplicationContext();
        ensureChannel(context);

        int smallIconId = context.getResources().getIdentifier(
                "ic_notification", "drawable", context.getPackageName());
        if (smallIconId == 0) {
            smallIconId = context.getApplicationInfo().icon;
        }

        Intent launchIntent = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
        if (launchIntent != null) {
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            launchIntent.putExtra("openRoute", "/notifications");
        }

        PendingIntent pendingIntent = null;
        if (launchIntent != null) {
            pendingIntent = PendingIntent.getActivity(
                    context, NOTIFICATION_ID, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        String title = "New Calendar Invitation" + (count > 1 ? "s" : "");
        String body = "You have " + count + " new invitation" + (count > 1 ? "s" : "");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(smallIconId)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent);
        }

        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
            Log.d(TAG, "Invitation notification fired: " + count + " invitation(s)");
        }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Calendar Invitations",
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("Notifications for new calendar event invitations");
                manager.createNotificationChannel(channel);
            }
        }
    }
}

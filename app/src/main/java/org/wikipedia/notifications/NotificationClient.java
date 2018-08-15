package org.wikipedia.notifications;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;

import com.google.gson.JsonParseException;

import org.wikipedia.WikipediaApp;
import org.wikipedia.csrf.CsrfTokenClient;
import org.wikipedia.dataclient.WikiSite;
import org.wikipedia.dataclient.mwapi.MwQueryResponse;
import org.wikipedia.dataclient.retrofit.MwCachedService;
import org.wikipedia.util.log.L;

import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Response;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Query;

public final class NotificationClient {

    public interface Callback {
        void success(@NonNull List<Notification> notifications, @Nullable String continueStr);
        void failure(Throwable t);
    }

    public interface UnreadWikisCallback {
        void success(@NonNull Map<String, Notification.UnreadNotificationWikiItem> wikiMap);
        void failure(Throwable t);
    }

    @NonNull private MwCachedService<Service> cachedService = new MwCachedService<>(Service.class);

    @VisibleForTesting static class CallbackAdapter implements retrofit2.Callback<MwQueryResponse> {
        @NonNull private final Callback callback;

        CallbackAdapter(@NonNull Callback callback) {
            this.callback = callback;
        }

        @Override public void onResponse(@NonNull Call<MwQueryResponse> call, @NonNull Response<MwQueryResponse> response) {
            if (response.body() != null && response.body().query() != null
                    && response.body().query().notifications() != null) {
                // noinspection ConstantConditions
                callback.success(response.body().query().notifications().list(), response.body().query().notifications().getContinue());
            } else {
                callback.failure(new JsonParseException("Notification response is malformed."));
            }
        }

        @Override public void onFailure(@NonNull Call<MwQueryResponse> call, @NonNull Throwable caught) {
            L.v(caught);
            callback.failure(caught);
        }
    }

    @VisibleForTesting static class UnreadCallbackAdapter implements retrofit2.Callback<MwQueryResponse> {
        @NonNull private final UnreadWikisCallback callback;

        UnreadCallbackAdapter(@NonNull UnreadWikisCallback callback) {
            this.callback = callback;
        }

        @Override public void onResponse(@NonNull Call<MwQueryResponse> call, @NonNull Response<MwQueryResponse> response) {
            if (response.body() != null && response.body().query() != null
                    && response.body().query().unreadNotificationWikis() != null) {
                // noinspection ConstantConditions
                callback.success(response.body().query().unreadNotificationWikis());
            } else {
                callback.failure(new JsonParseException("Notification response is malformed."));
            }
        }

        @Override public void onFailure(@NonNull Call<MwQueryResponse> call, @NonNull Throwable caught) {
            L.v(caught);
            callback.failure(caught);
        }
    }

    public void getUnreadNotificationWikis(@NonNull WikiSite wiki, @NonNull UnreadWikisCallback callback) {
        cachedService.service(wiki).getUnreadNotificationWikis().enqueue(new UnreadCallbackAdapter(callback));
    }

    public void getNotificationsWithForeignSummary(@NonNull WikiSite wiki, @NonNull final Callback callback) {
        cachedService.service(wiki).getForeignSummary().enqueue(new CallbackAdapter(callback));
    }

    public void getAllNotifications(@NonNull WikiSite wiki, @NonNull final Callback callback, boolean displayArchived,
                                    @Nullable String continueStr, @NonNull String... wikis) {
        cachedService.service(wiki).getAllNotifications(wikis.length > 0 ? TextUtils.join("|", wikis) : null,
                displayArchived ? "read" : "!read", TextUtils.isEmpty(continueStr) ? null : continueStr)
                .enqueue(new CallbackAdapter(callback));
    }

    public void markRead(@NonNull WikiSite wiki, @NonNull List<Notification> notifications) {
        markRead(wiki, notifications, false);
    }

    public void markUnread(@NonNull WikiSite wiki, @NonNull List<Notification> notifications) {
        markRead(wiki, notifications, true);
    }

    private void markRead(@NonNull WikiSite wiki, @NonNull List<Notification> notifications, boolean unread) {
        final String idListStr = TextUtils.join("|", notifications);
        CsrfTokenClient editTokenClient = new CsrfTokenClient(wiki, WikipediaApp.getInstance().getWikiSite());
        editTokenClient.request(new CsrfTokenClient.DefaultCallback() {
            @Override
            public void success(@NonNull String token) {
                cachedService.service(wiki).markRead(token, unread ? null : idListStr, unread ? idListStr : null)
                        .enqueue(new retrofit2.Callback<MwQueryResponse>() {
                            @Override
                            public void onResponse(@NonNull Call<MwQueryResponse> call, @NonNull Response<MwQueryResponse> response) {
                                // don't care about the response for now.
                            }

                            @Override
                            public void onFailure(@NonNull Call<MwQueryResponse> call, @NonNull Throwable t) {
                                L.e(t);
                            }
                        });
            }
        });
    }

    @VisibleForTesting @NonNull
    Call<MwQueryResponse> requestNotifications(@NonNull Service service, @NonNull String wikiList) {
        return service.getAllNotifications(wikiList, "!read", null);
    }

    @VisibleForTesting interface Service {
        String ACTION = "w/api.php?format=json&formatversion=2&action=";

        @Headers("Cache-Control: no-cache")
        @GET(ACTION + "query&meta=notifications&notcrosswikisummary=1&notlimit=1")
        @NonNull
        Call<MwQueryResponse> getForeignSummary();

        @Headers("Cache-Control: no-cache")
        @GET(ACTION + "query&meta=notifications&notformat=model&notlimit=max")
        @NonNull
        Call<MwQueryResponse> getAllNotifications(@Query("notwikis") @Nullable String wikiList,
                                                  @Query("notfilter") @Nullable String filter,
                                                  @Query("notcontinue") @Nullable String continueStr);

        @FormUrlEncoded
        @Headers("Cache-Control: no-cache")
        @POST(ACTION + "echomarkread")
        Call<MwQueryResponse> markRead(@Field("token") @NonNull String token, @Field("list") @Nullable String readList, @Field("unreadlist") @Nullable String unreadList);

        @Headers("Cache-Control: no-cache")
        @GET(ACTION + "query&meta=unreadnotificationpages&unplimit=max&unpwikis=*")
        @NonNull
        Call<MwQueryResponse> getUnreadNotificationWikis();
    }
}

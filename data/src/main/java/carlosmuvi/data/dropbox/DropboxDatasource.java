package carlosmuvi.data.dropbox;

import android.app.Activity;
import android.content.SharedPreferences;
import android.util.Log;
import carlosmuvi.bqsample.datasource.EbookDatasource;
import carlosmuvi.bqsample.model.Ebook;
import carlosmuvi.data.R;
import carlosmuvi.data.dropbox.exception.DropboxBookException;
import carlosmuvi.data.dropbox.exception.DropboxLoginException;
import carlosmuvi.data.dropbox.mapper.DropboxBookMapper;
import carlosmuvi.data.dropbox.model.DropboxBook;
import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.exception.DropboxException;
import com.dropbox.client2.session.AccessTokenPair;
import com.dropbox.client2.session.AppKeyPair;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.epub.EpubReader;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Func1;

/**
 * Created by carlos.
 */
public class DropboxDatasource implements EbookDatasource {

  public static final String DROPBOX_DATEFORMAT = "EEE, d MMM yyyy HH:mm:ss Z";
  public static final String DROPBOX_PREFKEY_NAME = "ACCESS_KEY_NAME";
  public static final String DROPBOX_PREFKEY_SECRET = "ACCESS_SECRET_NAME";
  public static final String EXT_EPUB = ".epub";

  private Activity activity;
  private DropboxBookMapper mapper;
  private DropboxAPI<AndroidAuthSession> dropboxAPI;

  @Inject public DropboxDatasource(Activity activity, DropboxBookMapper mapper) {

    this.activity = activity;
    this.mapper = mapper;

    AndroidAuthSession session = buildSession();
    dropboxAPI = new DropboxAPI<>(session);
  }

  /**
   * Start Dropbox login
   */
  @Override public void startLogin() {
    dropboxAPI.getSession().startOAuth2Authentication(activity);
  }

  @Override public Observable<String> completeLogin() {
    return Observable.create(new Observable.OnSubscribe<String>() {
      @Override public void call(Subscriber<? super String> subscriber) {
        if (dropboxAPI.getSession().authenticationSuccessful()) {
          try {
            dropboxAPI.getSession().finishAuthentication();
            storeAuth(dropboxAPI.getSession());
            subscriber.onCompleted();
          } catch (IllegalStateException e) {
            subscriber.onError(new DropboxLoginException());
          }
        } else {
          subscriber.onError(new DropboxLoginException());
        }
      }
    });
  }

  /**
   * Find all .epub files existing in current Dropbox Account
   */
  @Override public Observable<Ebook> listAllEbooks() {

    return Observable.create(new Observable.OnSubscribe<DropboxBook>() {
      @Override public void call(Subscriber<? super DropboxBook> subscriber) {

        // restore Dropbox authentication info
        if (dropboxAPI == null) {
          AndroidAuthSession session = buildSession();
          dropboxAPI = new DropboxAPI<>(session);
        }

        try {
          boolean hasMore = true;
          String cursor = null;

          while (hasMore) {
            DropboxAPI.DeltaPage<DropboxAPI.Entry> result = dropboxAPI.delta(cursor);
            cursor = result.cursor;
            hasMore = result.hasMore;

            //get all .epub files metadata from dropbox
            for (DropboxAPI.DeltaEntry<DropboxAPI.Entry> entry : result.entries) {

              if (isEpubFile(entry)) {

                //get book file stream from metadata
                DropboxAPI.DropboxInputStream file =
                    dropboxAPI.getFileStream(entry.metadata.path, null);

                //build DrobpoxBook object (book + file metadata)
                try {
                  DropboxBook dropboxBook = new DropboxBook();

                  //remove unnecessary content and set book
                  Book book = new EpubReader().readEpub(file);
                  book.setTableOfContents(null);
                  dropboxBook.setBook(book);

                  //set file metadata
                  SimpleDateFormat df = new SimpleDateFormat(DROPBOX_DATEFORMAT, Locale.US);
                  dropboxBook.setCreated(df.parse(entry.metadata.clientMtime));
                  dropboxBook.setPath(entry.metadata.path);

                  //notify added book
                  subscriber.onNext(dropboxBook);
                } catch (IOException e) {
                  Log.d("DEBUG", entry.metadata.fileName() + ": corrupted or non real epub file");
                } catch (ParseException e) {
                  Log.d("DEBUG",
                      entry.metadata.fileName() + ": error mapping creation date, skipping this ebbok");
                }
              }
            }
          }

        } catch (DropboxException e) {
          Log.e("ERROR", "error getting ebook list");
          subscriber.onError(new DropboxBookException());
        }
      }
    }).map(new DropboxBookMapper());

  }

  /**
   * @param entry: file to check if is epub
   * @return true if entry is epub file.
   */
  private boolean isEpubFile(DropboxAPI.DeltaEntry<DropboxAPI.Entry> entry) {
    return entry.metadata != null && !entry.metadata.isDir && entry.metadata.fileName()
        .substring(entry.metadata.fileName().length() - 5)
        .equalsIgnoreCase(EXT_EPUB);
  }

  /**
   * Shows keeping the access keys returned from Trusted Authenticator in a local
   * store, rather than storing user name & password, and re-authenticating each
   * time (which is not to be done, ever).
   */
  private void loadAuth(AndroidAuthSession session) {
    SharedPreferences prefs = activity.getSharedPreferences("ACCOUNT_PREFS_NAME", 0);
    String key = prefs.getString(DROPBOX_PREFKEY_NAME, null);
    String secret = prefs.getString(DROPBOX_PREFKEY_SECRET, null);
    if (key == null || secret == null || key.length() == 0 || secret.length() == 0) return;

    if (key.equals("oauth2:")) {
      // If the key is set to "oauth2:", then we can assume the token is for OAuth 2.
      session.setOAuth2AccessToken(secret);
    } else {
      // Still support using old OAuth 1 tokens.
      session.setAccessTokenPair(new AccessTokenPair(key, secret));
    }
  }

  /**
   * Shows keeping the access keys returned from Trusted Authenticator in a local
   * store, rather than storing user name & password, and re-authenticating each
   * time (which is not to be done, ever).
   */
  private void storeAuth(AndroidAuthSession session) {
    // Store the OAuth 2 access token, if there is one.
    String oauth2AccessToken = session.getOAuth2AccessToken();
    if (oauth2AccessToken != null) {
      SharedPreferences prefs = activity.getSharedPreferences("ACCOUNT_PREFS_NAME", 0);
      SharedPreferences.Editor edit = prefs.edit();
      edit.putString(DROPBOX_PREFKEY_NAME, "oauth2:");
      edit.putString(DROPBOX_PREFKEY_SECRET, oauth2AccessToken);
      edit.commit();
      return;
    }
    // Store the OAuth 1 access token, if there is one.  This is only necessary if
    // you're still using OAuth 1.
    AccessTokenPair oauth1AccessToken = session.getAccessTokenPair();
    if (oauth1AccessToken != null) {
      SharedPreferences prefs = activity.getSharedPreferences("ACCOUNT_PREFS_NAME", 0);
      SharedPreferences.Editor edit = prefs.edit();
      edit.putString(DROPBOX_PREFKEY_NAME, oauth1AccessToken.key);
      edit.putString(DROPBOX_PREFKEY_SECRET, oauth1AccessToken.secret);
      edit.commit();
      return;
    }
  }

  /**
   * Build Dropbox Authentication Session that will be used when comunicating with Dropbox servers.
   *
   * @return Dropbox Auth Session
   */
  private AndroidAuthSession buildSession() {
    AppKeyPair appKeyPair = new AppKeyPair(activity.getString(R.string.dropbox_key),
        activity.getString(R.string.dropbox_secret));

    AndroidAuthSession session = new AndroidAuthSession(appKeyPair);
    loadAuth(session);
    return session;
  }
}


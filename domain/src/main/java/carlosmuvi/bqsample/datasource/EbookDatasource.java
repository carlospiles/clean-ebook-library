package carlosmuvi.bqsample.datasource;

import java.util.List;

import carlosmuvi.bqsample.model.Ebook;

/**
 * Created by carlos.
 */
public interface EbookDatasource {


    void startLogin();

    void completeLogin(Callback callback);

    void listAllEbooks(EbookListCallback callback);

    /**
     * Generic callback
     */
    interface Callback {
        void onSuccess();

        void onError();
    }

    /**
     * Result of {@link #listAllEbooks(EbookListCallback)}
     */
    interface EbookListCallback {
        void onNext();

        void onSuccess(List<Ebook> books);

        void onError();
    }

}

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
package org.matrix.androidsdk.rest.callback;

import android.util.Log;

import org.matrix.androidsdk.rest.model.MatrixError;

import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;

public class RestAdapterCallback<T> implements Callback<T> {

    private static final String LOG_TAG = "RestAdapterCallback";

    private ApiCallback apiCallback;

    public RestAdapterCallback(ApiCallback apiCallback) {
        this.apiCallback = apiCallback;
    }

    @Override
    public void success(T t, Response response) {
        apiCallback.onSuccess(t);
    }

    /**
     * Default failure implementation that calls the right error handler
     * @param error
     */
    @Override
    public void failure(RetrofitError error) {
        Log.e(LOG_TAG, error.getMessage() + " url=" + error.getUrl());
        if (error.isNetworkError()) {
            apiCallback.onNetworkError(error);
        }
        else {
            // Try to convert this into a Matrix error
            MatrixError mxError;
            try {
                mxError = (MatrixError) error.getBodyAs(MatrixError.class);
            }
            catch (Exception e) {
                mxError = null;
            }
            if (mxError != null) {
                apiCallback.onMatrixError(mxError);
            }
            else {
                apiCallback.onUnexpectedError(error);
            }
        }
    }
}

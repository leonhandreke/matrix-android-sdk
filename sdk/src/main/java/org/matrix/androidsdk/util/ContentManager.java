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
package org.matrix.androidsdk.util;

import android.os.AsyncTask;
import android.util.Log;

import org.matrix.androidsdk.rest.model.ContentResponse;
import org.matrix.androidsdk.rest.model.ImageInfo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Class for accessing content from the current session.
 */
public class ContentManager {

    public static final String MATRIX_CONTENT_URI_SCHEME = "mxc://";

    public static final String METHOD_CROP = "crop";
    public static final String METHOD_SCALE = "scale";

    private static final String URI_PREFIX_CONTENT_API = "/_matrix/media/v1";

    private static final String LOG_TAG = "ContentManager";

    private String mHsUri;
    private String mAccessToken;

    /**
     * Interface to implement to get the mxc URI of uploaded content.
     */
    public static interface UploadCallback {

        /**
         * Called when the upload is complete or has failed.
         * @param uploadResponse the ContentResponse object containing the mxc URI or null if the upload failed
         */
        public void onUploadComplete(ContentResponse uploadResponse);
    }

    /**
     * Default constructor.
     * @param hsUri the home server URL
     * @param accessToken the user's access token
     */
    public ContentManager(String hsUri, String accessToken) {
        mHsUri = hsUri;
        mAccessToken = accessToken;
    }

    /**
     * Get an actual URL for accessing the full-size image of the given content URI.
     * @param contentUrl the mxc:// content URI
     * @return the URL to access the described resource
     */
    public String getDownloadableUrl(String contentUrl) {
        if (contentUrl == null) return null;
        if (contentUrl.startsWith(MATRIX_CONTENT_URI_SCHEME)) {
            String mediaServerAndId = contentUrl.substring(MATRIX_CONTENT_URI_SCHEME.length());
            return mHsUri + URI_PREFIX_CONTENT_API + "/download/" + mediaServerAndId;
        }
        else {
            return contentUrl;
        }
    }

    /**
     * Get an actual URL for accessing the thumbnail image of the given content URI.
     * @param contentUrl the mxc:// content URI
     * @param width the desired width
     * @param height the desired height
     * @param method the desired scale method (METHOD_CROP or METHOD_SCALE)
     * @return the URL to access the described resource
     */
    public String getDownloadableThumbnailUrl(String contentUrl, int width, int height, String method) {
        if (contentUrl == null) return null;
        if (contentUrl.startsWith(MATRIX_CONTENT_URI_SCHEME)) {
            String mediaServerAndId = contentUrl.substring(MATRIX_CONTENT_URI_SCHEME.length());
            String url = mHsUri + URI_PREFIX_CONTENT_API + "/thumbnail/" + mediaServerAndId;
            url += "?width=" + width;
            url += "&height=" + height;
            url += "&method=" + method;
            return url;
        }
        else {
            return contentUrl;
        }
    }

    /**
     * Upload a file.
     * @param fileName the file path
     * @param callback the async callback returning a mxc: URI to access the uploaded file
     */
    public void uploadContent(String fileName, UploadCallback callback) {
        new ContentUploadTask(callback).execute(fileName, mHsUri, mAccessToken);
    }

    /**
     * Private AsyncTask used to upload files.
     */
    private static class ContentUploadTask extends AsyncTask<String, Void, String> {

        private UploadCallback callback;

        public ContentUploadTask(UploadCallback callback) {
            this.callback = callback;
        }

        @Override
        protected String doInBackground(String... params) {
            HttpURLConnection conn;
            DataOutputStream dos;
            DataInputStream inStream;

            String fileName = params[0];
            String hsUrl = params[1];
            String accessToken = params[2];

            int bytesRead, bytesAvailable, bufferSize;

            byte[] buffer;

            int maxBufferSize = 1*1024*1024;

            String responseFromServer = null;

            String urlString = hsUrl + URI_PREFIX_CONTENT_API + "/upload?access_token=" + accessToken;

            try
            {
                FileInputStream fileInputStream = new FileInputStream(new File(fileName));

                URL url = new URL(urlString);

                conn = (HttpURLConnection) url.openConnection();
                conn.setDoInput(true);
                conn.setDoOutput(true);
                conn.setUseCaches(false);
                conn.setRequestMethod("POST");

                // TODO: Handle other file types
                String mimeType = ContentUtils.getMimeType(fileName);
                if (mimeType.startsWith("image/")) {
                    ImageInfo imageInfo = ContentUtils.getImageInfoFromFile(fileName);
                    conn.setRequestProperty("Content-type", imageInfo.mimetype);
                    conn.setRequestProperty("Content-length", String.valueOf(imageInfo.size));
                }

                conn.connect();

                dos = new DataOutputStream(conn.getOutputStream() );

                // create a buffer of maximum size
                bytesAvailable = fileInputStream.available();
                bufferSize = Math.min(bytesAvailable, maxBufferSize);
                buffer = new byte[bufferSize];

                // read file and write it into form...
                bytesRead = fileInputStream.read(buffer, 0, bufferSize);

                while (bytesRead > 0) {
                    dos.write(buffer, 0, bufferSize);
                    bytesAvailable = fileInputStream.available();
                    bufferSize = Math.min(bytesAvailable, maxBufferSize);
                    bytesRead = fileInputStream.read(buffer, 0, bufferSize);
                }

                // close streams
                fileInputStream.close();
                dos.flush();
                dos.close();

                // Read the SERVER RESPONSE
                int status = conn.getResponseCode();
                if (status == 200) {
                    InputStream is = conn.getInputStream();
                    int ch;
                    StringBuffer b = new StringBuffer();
                    while ((ch = is.read()) != -1) {
                        b.append((char) ch);
                    }
                    responseFromServer = b.toString();
                    is.close();
                }
                else {
                    Log.e(LOG_TAG, "Error: Upload returned " + status + " status code");
                    return null;
                }

            }
            catch (Exception e) {
                Log.e(LOG_TAG, "Error: " + e.getMessage());
            }

            return responseFromServer;
        }

        @Override
        protected void onPostExecute(String s) {
            callback.onUploadComplete((s == null) ? null : JsonUtils.toContentResponse(s));
        }
    }
}

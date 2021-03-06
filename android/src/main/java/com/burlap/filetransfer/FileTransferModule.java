package com.burlap.filetransfer;

import android.support.annotation.NonNull;
import android.util.Log;
import android.net.Uri;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReadableMap;

import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.MultipartBuilder;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

import java.io.File;

public class FileTransferModule extends ReactContextBaseJavaModule {

    private String TAG = "ImageUploadAndroid";
    private ReactApplicationContext reactContext;
    private int dataCounter = 0;
    private final int dataFilterSize = 50;

    public FileTransferModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        // match up with the IOS name
        return "FileTransfer";
    }

    @ReactMethod
    public void upload(final ReadableMap options, final Callback completeCallback) {
        // reset dataCounter
        dataCounter = 0;

        Thread thread = new Thread() {
            @Override
            public void run() {
                final OkHttpClient client = new OkHttpClient();

                try {
                    String fileKey = options.getString("fileKey");

                    // file from uri
                    String uri = options.getString("uri");
                    File file = getFile(uri);

                    if (!file.exists()) {
                        Log.d(TAG, "FILE NOT FOUND");
                        completeCallback.invoke("FILE NOT FOUND", null);
                        return;
                    }

                    String url = options.getString("uploadUrl");
                    String mimeType = options.getString("mimeType");
                    String fileName = options.getString("fileName");
                    ReadableMap headers = options.getMap("headers");
                    ReadableMap data = options.getMap("data");

                    Headers.Builder headerBuilder = createHeaders(headers);
                    RequestBody requestBody = createRequestBody(fileKey, file, fileName, data, mimeType);
                    requestBody = getCountingRequestBody(requestBody);

                    // ----- create request -----
                    Request request = new Request.Builder()
                      .headers(headerBuilder.build())
                      .url(url)
                      .post(requestBody)
                      .build();

                    // ----- execute -----
                    Response response = client.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        completeCallback.invoke(response, null);
                        return;
                    }

                    completeCallback.invoke(null, response.body().string());
                } catch (Exception e) {
                    Log.d(TAG, e.toString());
                    completeCallback.invoke(e.toString());
                }
            }
        };

        thread.start();
    }

    @NonNull
    private File getFile(String uri) {
        Uri file_uri = Uri.parse(uri);
        return new File(file_uri.getPath());
    }

    @NonNull
    private RequestBody createRequestBody(
            String fileKey, File file, String fileName, ReadableMap data, String mimeType
    ) {
        MediaType mediaType = MediaType.parse(mimeType);

        // add file data
        MultipartBuilder bodyBuilder = new MultipartBuilder();
        bodyBuilder.type(MultipartBuilder.FORM)
                .addPart(
                        Headers.of("Content-Disposition",
                                "form-data; name=\"" + fileKey + "\"; " +
                                        "filename=\"" + fileName + "\""
                        ),
                        RequestBody.create(mediaType, file)
                )
                .addPart(
                        Headers.of("Content-Disposition",
                                "form-data; name=\"filename\""
                        ),
                        RequestBody.create(null, fileName)
                );

        // add extra data
        ReadableMapKeySetIterator dataIterator = data.keySetIterator();
        while (dataIterator.hasNextKey()) {
            String key = dataIterator.nextKey();
            ReadableType type = data.getType(key);
            String value;
            switch(type) {
                case Null:
                    value = "null";
                    break;
                case Boolean:
                    value = String.valueOf(data.getBoolean(key));
                    break;
                case Number:
                    value = String.valueOf(data.getDouble(key));
                    break;
                case Array:
                    value = String.valueOf(data.getArray(key));
                    break;
                case Map:
                    value = String.valueOf(data.getMap(key));
                    break;
                default:
                    value = data.getString(key);
            }
            Log.d(TAG, "key=" + key + ", type=" + type + ", value=" + value);
            bodyBuilder.addFormDataPart(key, value);
        }

        return bodyBuilder.build();
    }

    private void incrementDataCounter() {
        dataCounter++;
        if (dataCounter > dataFilterSize)
            dataCounter = 0;
    }

    @NonNull
    private RequestBody getCountingRequestBody(RequestBody requestBody) {
        requestBody = new CountingRequestBody(requestBody, new CountingRequestBody.Listener() {
            @Override
            public void onRequestProgress(long bytesWritten, long contentLength) {
                if (contentLength <= 0) {
                    sendProgressJSEvent(0.9);
                } else if (dataCounter % dataFilterSize == 0) {
                    Log.d(TAG, bytesWritten + "/" + contentLength);
                    sendProgressJSEvent((double) bytesWritten / contentLength);
                }
                incrementDataCounter();
            }
        });
        return requestBody;
    }

    @NonNull
    private Headers.Builder createHeaders(ReadableMap headers) {
        Headers.Builder headerBuilder = new Headers.Builder();
        ReadableMapKeySetIterator headerIterator = headers.keySetIterator();
        while (headerIterator.hasNextKey()) {
            String key = headerIterator.nextKey();
            String value = headers.getString(key);
            headerBuilder.add(key, value);
        }
        return headerBuilder;
    }

    private void sendProgressJSEvent(double progress) {
        WritableMap map = Arguments.createMap();
        map.putDouble("progress", progress);

        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("upload_progress", map);
    }
}

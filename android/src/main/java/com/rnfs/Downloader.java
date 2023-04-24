package com.rnfs;

import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import android.util.Log;

import android.os.AsyncTask;

import com.facebook.react.bridge.ReadableMapKeySetIterator;

public class Downloader extends AsyncTask<DownloadParams, int[], DownloadResult> {
  private DownloadParams mParam;
  private AtomicBoolean mAbort = new AtomicBoolean(false);
  DownloadResult res;

  protected DownloadResult doInBackground(DownloadParams... params) {
    mParam = params[0];
    res = new DownloadResult();

    new Thread(new Runnable() {
      public void run() {
        try {
          download(mParam, res);
          mParam.onTaskCompleted.onTaskCompleted(res);
        } catch (Exception ex) {
          res.exception = ex;
          mParam.onTaskCompleted.onTaskCompleted(res);
        }
      }
    }).start();

    return res;
  }

  private void setTls(HttpsURLConnection connection, String spec) throws NoSuchAlgorithmException, KeyManagementException {
    SSLContext sc = SSLContext.getInstance(spec);
    sc.init(null, null, new java.security.SecureRandom());
    connection.setSSLSocketFactory(sc.getSocketFactory());
  }

  private void download(DownloadParams param, DownloadResult res) throws Exception {
    InputStream input = null;
    OutputStream output = null;
    HttpURLConnection connection = null;

    try {
      connection = (HttpURLConnection)param.src.openConnection();

      Log.d("Downloader", "Protocol: " + param.src.getProtocol());
      HttpsURLConnection httpsConnection = (HttpsURLConnection)connection;
      if (httpsConnection != null) {
        // When calling AWS s3;
        // with this call we get TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 (TLS1.2)
        // otherwise we get      TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA    (TLS1.0, TLS1.1, TLS1.2)
        setTls(httpsConnection, "TLSv1.2");
      }

      ReadableMapKeySetIterator iterator = param.headers.keySetIterator();

      while (iterator.hasNextKey()) {
        String key = iterator.nextKey();
        String value = param.headers.getString(key);
        connection.setRequestProperty(key, value);
      }

      connection.setConnectTimeout(param.connectionTimeout);
      connection.setReadTimeout(param.readTimeout);
      connection.connect();

      if (httpsConnection != null) {
        Log.d("Downloader", "HTTPS: " + httpsConnection.getCipherSuite());
      }

      int statusCode = connection.getResponseCode();
      int lengthOfFile = connection.getContentLength();

      boolean isRedirect = (
        statusCode != HttpURLConnection.HTTP_OK &&
        (
          statusCode == HttpURLConnection.HTTP_MOVED_PERM ||
          statusCode == HttpURLConnection.HTTP_MOVED_TEMP ||
          statusCode == 307 ||
          statusCode == 308
        )
      );

      if (isRedirect) {
        String redirectURL = connection.getHeaderField("Location");
        connection.disconnect();

        URL redirect = new URL(redirectURL);
        connection = (HttpURLConnection) redirect.openConnection();

        httpsConnection = (HttpsURLConnection)connection;
        if (httpsConnection != null) {
          setTls(httpsConnection, "TLSv1.2");
        }

        connection.setConnectTimeout(5000);
        connection.connect();

        statusCode = connection.getResponseCode();
        lengthOfFile = connection.getContentLength();
      }
      if(statusCode >= 200 && statusCode < 300) {
        Map<String, List<String>> headers = connection.getHeaderFields();

        Map<String, String> headersFlat = new HashMap<String, String>();

        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
          String headerKey = entry.getKey();
          String valueKey = entry.getValue().get(0);

          if (headerKey != null && valueKey != null) {
            headersFlat.put(headerKey, valueKey);
          }
        }

        mParam.onDownloadBegin.onDownloadBegin(statusCode, lengthOfFile, headersFlat);

        int blockSize = 8 * 1024;

        input = new BufferedInputStream(connection.getInputStream(), blockSize);
        output = new FileOutputStream(param.dest);

        byte data[] = new byte[blockSize];
        int total = 0;
        int count;
        double lastProgressValue = 0;
        double throttleDuration = param.throttleRate > 0 ? (blockSize / (double) param.throttleRate) : 0;
        long throttleMillis = (long) (throttleDuration * 1e3);
        int throttleNanos = (int) (throttleDuration * 1e6) % 1000;
        Log.d("Downloader", "THROTTLE MS: " + String.valueOf(throttleMillis) + " NS: " + String.valueOf(throttleNanos));

        while ((count = input.read(data)) != -1) {
          if (mAbort.get()) throw new Exception("Download has been aborted");

          total += count;
          if (param.progressDivider <= 0) {
            publishProgress(new int[]{lengthOfFile, total});
          } else {
            double progress = Math.round(((double) total * 100) / lengthOfFile);
            if (progress % param.progressDivider == 0) {
              if ((progress != lastProgressValue) || (total == lengthOfFile)) {
                Log.d("Downloader", "EMIT: " + String.valueOf(progress) + ", TOTAL:" + String.valueOf(total));
                lastProgressValue = progress;
                publishProgress(new int[]{lengthOfFile, total});
              }
            }
          }
          output.write(data, 0, count);

          if (throttleMillis > 0) {
            Thread.sleep(throttleMillis, throttleNanos);
          }
        }

        output.flush();
        res.bytesWritten = total;
      }
      res.statusCode = statusCode;
    } finally {
      if (output != null) output.close();
      if (input != null) input.close();
      if (connection != null) connection.disconnect();
    }
  }

  protected void stop() {
    mAbort.set(true);
  }

  @Override
  protected void onProgressUpdate(int[]... values) {
    super.onProgressUpdate(values);
    mParam.onDownloadProgress.onDownloadProgress(values[0][0], values[0][1]);
  }

  protected void onPostExecute(Exception ex) {

  }
}

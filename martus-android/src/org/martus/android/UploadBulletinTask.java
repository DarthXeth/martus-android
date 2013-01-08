package org.martus.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import org.martus.clientside.ClientSideNetworkGateway;
import org.martus.common.MartusUtilities;
import org.martus.common.crypto.MartusCrypto;
import org.martus.common.crypto.MartusSecurity;
import org.martus.common.network.NetworkInterfaceConstants;
import org.martus.common.network.NetworkResponse;
import org.martus.common.packet.UniversalId;
import org.martus.util.StreamableBase64;

import android.os.AsyncTask;
import android.text.format.Time;
import android.util.Log;

/**
 * @author roms
 *         Date: 10/3/12
 */
public class UploadBulletinTask extends AsyncTask<Object, Integer, String> {

    private NotificationHelper mNotificationHelper;
    private BulletinSender sender;
    private MartusApplication myApplication;

    public UploadBulletinTask(MartusApplication application, BulletinSender sender, UniversalId bulletinId) {
        myApplication = application;
        mNotificationHelper = new NotificationHelper(myApplication.getApplicationContext(), bulletinId.hashCode());
        this.sender = sender;
    }

    @Override
    protected String doInBackground(Object... params) {

        final UniversalId uid = (UniversalId)params[0];
        final File zippedFile = (File)params[1];
        final ClientSideNetworkGateway gateway = (ClientSideNetworkGateway)params[2];
        final MartusSecurity signer = (MartusSecurity)params[3];

        String result = null;

        try {
            result = uploadBulletinZipFile(uid, zippedFile, gateway, signer);
        } catch (MartusUtilities.FileTooLargeException e) {
            Log.e(AppConfig.LOG_LABEL, "file too large to upload", e);
            result = e.getMessage();
        } catch (IOException e) {
            Log.e(AppConfig.LOG_LABEL, "io problem uploading file", e);
            result = e.getMessage();
        } catch (MartusCrypto.MartusSignatureException e) {
            Log.e(AppConfig.LOG_LABEL, "crypto problem uploading file", e);
            result = e.getMessage();
        } finally {
            if (null != zippedFile && (null != result) && (result.equals(NetworkInterfaceConstants.OK))) {
                Log.i(AppConfig.LOG_LABEL, "deleting zipped bulletin on successful send");
                zippedFile.delete();
            }
        }


        return result;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        Time now = new Time();
        now.setToNow();
        String timeAsTitle = now.format("%T");
        mNotificationHelper.createNotification(myApplication.getString(R.string.notification_title, timeAsTitle),
                myApplication.getString(R.string.starting_send_notification));
    }

    @Override
    protected void onPostExecute(String s) {
        mNotificationHelper.completed(s);
        if (null != sender) {
            sender.onSent();
        }
        myApplication.setIgnoreInactivity(false);
        myApplication.resetInactivityTimer();
        super.onPostExecute(s);
    }

    @Override
    protected void onProgressUpdate(Integer... progress) {
        super.onProgressUpdate(progress);
        if (null != sender) {
            sender.onProgressUpdate(progress[0]);
            mNotificationHelper.updateProgress(myApplication.getString(R.string.bulletin_sending_progress),   progress[0]);
        }
    }

    private String uploadBulletinZipFile(UniversalId uid, File tempFile, ClientSideNetworkGateway gateway, MartusCrypto crypto)
        		throws
                    MartusUtilities.FileTooLargeException, IOException, MartusCrypto.MartusSignatureException
    {
        int totalSize = MartusUtilities.getCappedFileLength(tempFile);
        int offset = 0;
        byte[] rawBytes = new byte[NetworkInterfaceConstants.CLIENT_MAX_CHUNK_SIZE];
        FileInputStream inputStream = new FileInputStream(tempFile);
        String result = null;
        while(true)
        {
            int chunkSize = inputStream.read(rawBytes);
            if(chunkSize <= 0)
                break;
            byte[] chunkBytes = new byte[chunkSize];
            System.arraycopy(rawBytes, 0, chunkBytes, 0, chunkSize);

            String authorId = uid.getAccountId();
            String bulletinLocalId = uid.getLocalId();
            String encoded = StreamableBase64.encode(chunkBytes);

            NetworkResponse response = gateway.putBulletinChunk(crypto,
                                authorId, bulletinLocalId, totalSize, offset, chunkSize, encoded);
            result = response.getResultCode();
            if(!result.equals(NetworkInterfaceConstants.CHUNK_OK) && !result.equals(NetworkInterfaceConstants.OK))
                break;
            offset += chunkSize;

            publishProgress(offset * 100 / totalSize);
        }
        inputStream.close();
        return result;
    }
}

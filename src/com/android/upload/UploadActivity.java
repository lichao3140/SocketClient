package com.android.upload;

import java.io.File;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.android.service.UploadLogService;
import com.android.socket.utils.StreamTool;

/**
 * socket大文件断点上传客服端
 * 
 * @author dell
 * 
 */
@TargetApi(Build.VERSION_CODES.KITKAT)
@SuppressLint("NewApi")
public class UploadActivity extends Activity {
	private EditText filenameText;
	private TextView resulView;
	private ProgressBar uploadbar;
	private UploadLogService logService;
	private boolean start = true;
	private String path;
	
	private Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			int length = msg.getData().getInt("size");
			uploadbar.setProgress(length);
			float num = (float) uploadbar.getProgress()
					/ (float) uploadbar.getMax();
			int result = (int) (num * 100);
			resulView.setText(result + "%");
			if (uploadbar.getProgress() == uploadbar.getMax()) {
				Toast.makeText(UploadActivity.this, R.string.success, 1).show();
			}
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		
		logService = new UploadLogService(this);
		filenameText = (EditText) this.findViewById(R.id.filename);
		uploadbar = (ProgressBar) this.findViewById(R.id.uploadbar);
		resulView = (TextView) this.findViewById(R.id.result);
		Button button = (Button) this.findViewById(R.id.button);
		Button select_fileButton = (Button) this
				.findViewById(R.id.id_select_file);
		Button button1 = (Button) this.findViewById(R.id.stop);
		button1.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				start = false;

			}
		});
		select_fileButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				Intent intent = new Intent();
				if (Build.VERSION.SDK_INT < 19) {
					intent.setAction(Intent.ACTION_GET_CONTENT);
				} else {
					// 由于Intent.ACTION_OPEN_DOCUMENT的版本是4.4以上的内容
					// 如果客户使用的不是4.4以上的版本，因为前面有判断，所以根本不会走else，
					// 也就不会出现任何因为这句代码引发的错误
					intent.setAction(Intent.ACTION_OPEN_DOCUMENT);
				}
				intent.setType("image/*");//图片
				//intent.setType("video/*");//视频
				//intent.setType("audio/amr");//录音				
				startActivityForResult(intent, 1);
			}
		});
		button.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				start = true;
				String filename = filenameText.getText().toString();
				if (Environment.getExternalStorageState().equals(
						Environment.MEDIA_MOUNTED)) {
					File uploadFile = new File(Environment
							.getExternalStorageDirectory(), filename);
					if (uploadFile.exists()) {
						uploadFile(uploadFile);
					} else {
						Toast.makeText(UploadActivity.this,
								R.string.filenotexsit, 1).show();
					}
				} else {
					Toast.makeText(UploadActivity.this, R.string.sdcarderror, 1)
							.show();
				}
			}
		});
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		// TODO Auto-generated method stub
		super.onActivityResult(requestCode, resultCode, data);
		path = getPathByUri(this, data.getData());
		System.out.println("path:"+path);
		filenameText.setText(path);
		
	}

	/**
	 * 上传文件
	 * 
	 * @param uploadFile
	 */
	private void uploadFile(final File uploadFile) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					uploadbar.setMax((int) uploadFile.length());
					String souceid = logService.getBindId(uploadFile);
					String head = "Content-Length=" + uploadFile.length()
							+ ";filename=" + uploadFile.getName()
							+ ";sourceid=" + (souceid == null ? "" : souceid)
							+ "\r\n";
					Socket socket = new Socket("192.168.2.111", 7878);
					OutputStream outStream = socket.getOutputStream();
					outStream.write(head.getBytes());

					PushbackInputStream inStream = new PushbackInputStream(
							socket.getInputStream());
					String response = StreamTool.readLine(inStream);
					String[] items = response.split(";");
					String responseid = items[0].substring(items[0]
							.indexOf("=") + 1);
					String position = items[1].substring(items[1].indexOf("=") + 1);
					if (souceid == null) {// 代表原来没有上传过此文件，往数据库添加一条绑定记录
						logService.save(responseid, uploadFile);
					}
					RandomAccessFile fileOutStream = new RandomAccessFile(
							uploadFile, "r");
					fileOutStream.seek(Integer.valueOf(position));
					byte[] buffer = new byte[1024];
					int len = -1;
					int length = Integer.valueOf(position);
					while (start && (len = fileOutStream.read(buffer)) != -1) {
						outStream.write(buffer, 0, len);
						length += len;
						Message msg = new Message();
						msg.getData().putInt("size", length);
						handler.sendMessage(msg);
					}
					fileOutStream.close();
					outStream.close();
					inStream.close();
					socket.close();
					if (length == uploadFile.length())
						logService.delete(uploadFile);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}).start();
	}

	@TargetApi(Build.VERSION_CODES.KITKAT)
	@SuppressLint("NewApi")
	public static String getPathByUri(Context cxt, Uri uri) {
		// 判断手机系统是否是4.4或以上的sdk
		final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
		// 如果是4.4以上的系统并且选择的文件是4.4专有的最近的文件
		if (isKitKat && DocumentsContract.isDocumentUri(cxt, uri)) {
			// 如果是从外部储存卡选择的文件
			if (isExternalStorageDocument(uri)) {
				final String docId = DocumentsContract.getDocumentId(uri);
				final String[] split = docId.split(":");
				final String type = split[0];

				if ("primary".equalsIgnoreCase(type)) {
					return Environment.getExternalStorageDirectory() + "/"
							+ split[1];
				}

			}
			// 如果是下载返回的路径
			else if (isDownloadsDocument(uri)) {
				final String id = DocumentsContract.getDocumentId(uri);
				final Uri contentUri = ContentUris.withAppendedId(
						Uri.parse("content://downloads/public_downloads"),
						Long.valueOf(id));

				return getDataColumn(cxt, contentUri, null, null);
			}
			// 如果是选择的媒体的文件
			else if (isMediaDocument(uri)) {
				final String docId = DocumentsContract.getDocumentId(uri);
				final String[] split = docId.split(":");
				final String type = split[0];

				Uri contentUri = null;
				if ("image".equals(type)) { // 图片
					contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
				} else if ("video".equals(type)) { // 视频
					contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
				} else if ("audio".equals(type)) { // 音频
					contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
				}

				final String selection = "_id=?";
				final String[] selectionArgs = new String[] { split[1] };

				return getDataColumn(cxt, contentUri, selection, selectionArgs);
			}
		} else if ("content".equalsIgnoreCase(uri.getScheme())) { // 如果是低端4.2以下的手机文件uri格式
			if (isGooglePhotosUri(uri))
				return uri.getLastPathSegment();

			return getDataColumn(cxt, uri, null, null);
		} else if ("file".equalsIgnoreCase(uri.getScheme())) { // 如果是通过file转成的uri的格式
			return uri.getPath();
		}

		return null;
	}

	public static String getDataColumn(Context context, Uri uri,
			String selection, String[] selectionArgs) {

		Cursor cursor = null;
		final String column = "_data";
		final String[] projection = { column };

		try {
			cursor = context.getContentResolver().query(uri, projection,
					selection, selectionArgs, null);
			if (cursor != null && cursor.moveToFirst()) {
				final int index = cursor.getColumnIndexOrThrow(column);
				return cursor.getString(index);
			}
		} finally {
			if (cursor != null)
				cursor.close();
		}
		return null;
	}

	public static boolean isExternalStorageDocument(Uri uri) {
		return "com.android.externalstorage.documents".equals(uri
				.getAuthority());
	}

	public static boolean isDownloadsDocument(Uri uri) {
		return "com.android.providers.downloads.documents".equals(uri
				.getAuthority());
	}

	public static boolean isMediaDocument(Uri uri) {
		return "com.android.providers.media.documents".equals(uri
				.getAuthority());
	}

	public static boolean isGooglePhotosUri(Uri uri) {
		return "com.google.android.apps.photos.content".equals(uri
				.getAuthority());
	}

}
/*
The contents of this file are subject to the Mozilla Public License
Version 1.1 (the "License"); you may not use this file except in
compliance with the License. You may obtain a copy of the License at
http://www.mozilla.org/MPL/

Software distributed under the License is distributed on an "AS IS"
basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
License for the specific language governing rights and limitations
under the License.

The Original Code is collection of files collectively known as Open Camera.

The Initial Developer of the Original Code is Almalence Inc.
Portions created by Initial Developer are Copyright (C) 2013 
by Almalence Inc. All Rights Reserved.
 */

package com.almalence.plugins.capture.burst;

import java.nio.ByteBuffer;
import java.util.Arrays;

import android.annotation.TargetApi;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.os.CountDownTimer;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;

/* <!-- +++
 import com.almalence.opencam_plus.cameracontroller.CameraController;
 import com.almalence.opencam_plus.MainScreen;
 import com.almalence.opencam_plus.PluginCapture;
 import com.almalence.opencam_plus.PluginManager;
 import com.almalence.opencam_plus.R;
 +++ --> */
// <!-- -+-
import com.almalence.opencam.cameracontroller.CameraController;
import com.almalence.opencam.MainScreen;
import com.almalence.opencam.PluginCapture;
import com.almalence.opencam.PluginManager;
import com.almalence.opencam.R;
//-+- -->

import com.almalence.SwapHeap;
import com.almalence.YuvImage;

/***
 * Implements burst capture plugin - captures predefined number of images
 ***/

public class BurstCapturePlugin extends PluginCapture
{
	// defaul val. value should come from config
	private int				imageAmount			= 3;
	private int				pauseBetweenShots	= 0;

	private static String	sImagesAmountPref;
	private static String	sPauseBetweenShotsPref;

	public BurstCapturePlugin()
	{
		super("com.almalence.plugins.burstcapture", R.xml.preferences_capture_burst, 0,
				R.drawable.gui_almalence_mode_burst, "Burst images");
	}

	@Override
	public void onCreate()
	{
		sImagesAmountPref = MainScreen.getAppResources().getString(R.string.Preference_BurstImagesAmount);
		sPauseBetweenShotsPref = MainScreen.getAppResources()
				.getString(R.string.Preference_BurstPauseBetweenShots);
	}

	@Override
	public void onResume()
	{
		takingAlready = false;
		imagesTaken = 0;
		inCapture = false;
		refreshPreferences();
		MainScreen.setCaptureYUVFrames(false);
	}

	private void refreshPreferences()
	{
		try
		{
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
			imageAmount = Integer.parseInt(prefs.getString(sImagesAmountPref, "3"));
			pauseBetweenShots = Integer.parseInt(prefs.getString(sPauseBetweenShotsPref, "0"));
		} catch (Exception e)
		{
			Log.e("Burst capture", "Cought exception " + e.getMessage());
		}

		switch (imageAmount)
		{
		case 3:
			quickControlIconID = R.drawable.gui_almalence_mode_burst3;
			break;
		case 5:
			quickControlIconID = R.drawable.gui_almalence_mode_burst5;
			break;
		case 10:
			quickControlIconID = R.drawable.gui_almalence_mode_burst10;
			break;
		case 15:
			quickControlIconID = R.drawable.gui_almalence_mode_burst15;
			break;
		case 20:
			quickControlIconID = R.drawable.gui_almalence_mode_burst20;
			break;
		default:
			break;
		}
	}

	@Override
	public void onQuickControlClick()
	{
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		int val = Integer.parseInt(prefs.getString(sImagesAmountPref, "1"));
		int selected = 0;
		switch (val)
		{
		case 3:
			selected = 0;
			break;
		case 5:
			selected = 1;
			break;
		case 10:
			selected = 2;
			break;
		case 15:
			selected = 3;
			break;
		case 20:
			selected = 4;
			break;
		default:
			break;
		}
		selected = (selected + 1) % 5;

		Editor editor = prefs.edit();
		switch (selected)
		{
		case 0:
			quickControlIconID = R.drawable.gui_almalence_mode_burst3;
			editor.putString("burstImagesAmount", "3");
			break;
		case 1:
			quickControlIconID = R.drawable.gui_almalence_mode_burst5;
			editor.putString("burstImagesAmount", "5");
			break;
		case 2:
			quickControlIconID = R.drawable.gui_almalence_mode_burst10;
			editor.putString("burstImagesAmount", "10");
			break;
		case 3:
			quickControlIconID = R.drawable.gui_almalence_mode_burst15;
			editor.putString("burstImagesAmount", "15");
			break;
		case 4:
			quickControlIconID = R.drawable.gui_almalence_mode_burst20;
			editor.putString("burstImagesAmount", "20");
			break;
		default:
			break;
		}
		editor.commit();
	}

	public boolean delayedCaptureSupported()
	{
		return true;
	}

	public void takePicture()
	{
		refreshPreferences();
		inCapture = true;
		takingAlready = true;
		
		try
		{
			int[] pause = new int[imageAmount];
			Arrays.fill(pause, pauseBetweenShots);
			requestID = CameraController.captureImagesWithParams(imageAmount, CameraController.JPEG, pause, null, true);
		} catch (Exception e)
		{
			e.printStackTrace();
			Log.e("CameraController.captureImagesWithParams failed", "takePicture: " + e.getMessage());
			inCapture = false;
			takingAlready = false;
			PluginManager.getInstance().sendMessage(PluginManager.MSG_BROADCAST, 
					PluginManager.MSG_CONTROL_UNLOCKED);
			MainScreen.getGUIManager().lockControls = false;
		}
	}

	
	@Override
	public void onImageTaken(int frame, byte[] frameData, int frame_len, boolean isYUV)
	{
		imagesTaken++;

		if (frame == 0)
		{
			Log.d("Burst", "Load to heap failed");
			PluginManager.getInstance().sendMessage(PluginManager.MSG_CAPTURE_FINISHED, 
					String.valueOf(SessionID));

			imagesTaken = 0;
			MainScreen.getInstance().muteShutter(false);
			return;
		}
		String frameName = "frame" + imagesTaken;
		String frameLengthName = "framelen" + imagesTaken;

		PluginManager.getInstance().addToSharedMem(frameName + SessionID, String.valueOf(frame));
		PluginManager.getInstance().addToSharedMem(frameLengthName + SessionID, String.valueOf(frame_len));
		PluginManager.getInstance().addToSharedMem("frameorientation" + imagesTaken + SessionID,
				String.valueOf(MainScreen.getGUIManager().getDisplayOrientation()));
		PluginManager.getInstance().addToSharedMem("framemirrored" + imagesTaken + SessionID,
				String.valueOf(CameraController.isFrontCamera()));

		try
		{
			CameraController.startCameraPreview();
		} catch (RuntimeException e)
		{
			Log.e("Burst", "StartPreview fail");
			PluginManager.getInstance().sendMessage(PluginManager.MSG_CAPTURE_FINISHED, 
					String.valueOf(SessionID));

			imagesTaken = 0;
			MainScreen.getInstance().muteShutter(false);
			return;
		}

		if (imagesTaken >= imageAmount)
		{
			PluginManager.getInstance().addToSharedMem("amountofcapturedframes" + SessionID,
					String.valueOf(imagesTaken));

			PluginManager.getInstance().sendMessage(PluginManager.MSG_CAPTURE_FINISHED, 
					String.valueOf(SessionID));

			imagesTaken = 0;
			
			takingAlready = false;
			inCapture = false;
		}
				
	}

	@TargetApi(21)
	@Override
	public void onCaptureCompleted(CaptureResult result)
	{
		if (result.getSequenceId() == requestID)
		{
			if (imagesTaken == 1)
				PluginManager.getInstance().addToSharedMemExifTagsFromCaptureResult(result, SessionID);
		}
	}
	
	@Override
	public void onExportFinished()
	{
		
	}

	@Override
	public void onAutoFocus(boolean paramBoolean)
	{
		if (takingAlready)
			takePicture();
	}


	@Override
	public void onPreviewFrame(byte[] data)
	{
	}
}

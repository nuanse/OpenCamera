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

package com.almalence.plugins.capture.standard;

import android.annotation.TargetApi;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup.LayoutParams;
import android.widget.CompoundButton;
import android.widget.RelativeLayout;
import android.hardware.camera2.CaptureResult;

/* <!-- +++
 import com.almalence.opencam_plus.cameracontroller.CameraController;
 import com.almalence.opencam_plus.MainScreen;
 import com.almalence.opencam_plus.PluginCapture;
 import com.almalence.opencam_plus.PluginManager;
 import com.almalence.opencam_plus.R;
 +++ --> */
// <!-- -+-
import com.almalence.opencam.MainScreen;
import com.almalence.opencam.PluginCapture;
import com.almalence.opencam.PluginManager;
import com.almalence.opencam.R;
import com.almalence.opencam.cameracontroller.CameraController;
//-+- -->
import com.almalence.ui.Switch.Switch;

/***
 * Implements standard capture plugin - capture single image and save it in
 * shared memory
 ***/

public class CapturePlugin extends PluginCapture
{
	private static String		ModePreference;													// 0=DRO
																									// On
																									// 1=DRO
																									// Off
	private Switch				modeSwitcher;
	public static final String	CAMERA_IMAGE_BUCKET_NAME	= Environment.getExternalStorageDirectory().toString()
																	+ "/DCIM/Camera/tmp_raw_img";

	private int					singleModeEV;
	private int					droEvDiff;

	public CapturePlugin()
	{
		super("com.almalence.plugins.capture", 0, 0, 0, null);
	}

	void UpdateEv(boolean isDro, int ev)
	{
		if (isDro)
		{
			// for still-image DRO - set Ev just a bit lower (-0.5Ev or less)
			// than for standard shot
			float expStep = CameraController.getInstance().getExposureCompensationStep();
			int diff = (int) Math.floor(0.5 / expStep);
			if (diff < 1)
				diff = 1;

			droEvDiff = diff;
			ev -= diff;
		}

		int minValue = CameraController.getInstance().getMinExposureCompensation();
		if (ev >= minValue)
		{
			//Log.d("Capture", "UpdateEv. isDRO = " + isDro + " EV = " + ev);
			CameraController.getInstance().setCameraExposureCompensation(ev);
		}
	}

	@Override
	public void onCreate()
	{
		LayoutInflater inflator = MainScreen.getInstance().getLayoutInflater();
		modeSwitcher = (Switch) inflator.inflate(R.layout.plugin_capture_standard_modeswitcher, null, false);

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		ModePreference = prefs.getString("modeStandardPref", "1");
		modeSwitcher.setTextOn("DRO On");
		modeSwitcher.setTextOff("DRO Off");
		modeSwitcher.setChecked(ModePreference.compareTo("0") == 0 ? true : false);
		modeSwitcher.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener()
		{
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isDro)
			{

				SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());

				if (isDro)
				{
					singleModeEV = prefs.getInt(MainScreen.sEvPref, 0);
					//Log.d("Capture", "onCheckedChanged. isDro = true singleModeEV = " + singleModeEV);

					ModePreference = "0";
					MainScreen.setCaptureYUVFrames(true);
				} else
				{
					ModePreference = "1";
					MainScreen.setCaptureYUVFrames(false);

//					Log.d("Capture", "onCheckedChanged. isDro = false singleModeEV = " + singleModeEV);
				}

				// UpdateEv(isDro, isDro? singleModeEV :
				// (singleModeEV+droEvDiff));
				UpdateEv(isDro, singleModeEV);

				SharedPreferences.Editor editor = prefs.edit();
				editor.putString("modeStandardPref", ModePreference);
				editor.commit();

				MainScreen.getInstance().relaunchCamera();

				if (ModePreference.compareTo("0") == 0)
					MainScreen.getGUIManager().showHelp(MainScreen.getInstance().getString(R.string.Dro_Help_Header),
							MainScreen.getAppResources().getString(R.string.Dro_Help),
							R.drawable.plugin_help_dro, "droShowHelp");
			}
		});

		if (PluginManager.getInstance().getProcessingCounter() == 0)
			modeSwitcher.setEnabled(true);
	}

	@Override
	public void onCameraParametersSetup()
	{
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		singleModeEV = prefs.getInt(MainScreen.sEvPref, 0);
//		Log.d("Capture", "onCameraParametersSetup. singleModeEV = " + singleModeEV);

		if (ModePreference.compareTo("0") == 0)
		{
			// FixMe: why not setting exposure if we are in dro-off mode?
			UpdateEv(true, singleModeEV);
		}
	}

	@Override
	public void onStart()
	{
		// Get the xml/preferences.xml preferences
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		ModePreference = prefs.getString("modeStandardPref", "1");
	}

	@Override
	public void onResume()
	{
		if (ModePreference.compareTo("0") == 0)
			MainScreen.setCaptureYUVFrames(true);
		else
			MainScreen.setCaptureYUVFrames(false);
	}

	@Override
	public void onPause()
	{
//		Log.d("Capture", "onPause");
		if (ModePreference.contains("0"))
		{
			UpdateEv(false, singleModeEV);
		}
	}

	@Override
	public void onGUICreate()
	{
		MainScreen.getGUIManager().removeViews(modeSwitcher, R.id.specialPluginsLayout3);

		RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT,
				LayoutParams.WRAP_CONTENT);

		params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
		params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);

		((RelativeLayout) MainScreen.getInstance().findViewById(R.id.specialPluginsLayout3)).addView(this.modeSwitcher,
				params);

		this.modeSwitcher.setLayoutParams(params);
		// this.modeSwitcher.requestLayout();
		//
		// ((RelativeLayout)
		// MainScreen.getInstance().findViewById(R.id.specialPluginsLayout3)).requestLayout();

		if (ModePreference.compareTo("0") == 0)
			MainScreen.getGUIManager().showHelp("Dro help",
					MainScreen.getAppResources().getString(R.string.Dro_Help), R.drawable.plugin_help_dro,
					"droShowHelp");
	}

	@Override
	public void onStop()
	{
		MainScreen.getGUIManager().removeViews(modeSwitcher, R.id.specialPluginsLayout3);
	}

	@Override
	public void onDefaultsSelect()
	{
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		ModePreference = prefs.getString("modeStandardPref", "1");
	}

	@Override
	public void onShowPreferences()
	{
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		ModePreference = prefs.getString("modeStandardPref", "1");
	}

	@Override
	public void takePicture()
	{
//		Log.d("CapturePlugin", "takePicture");
		if (!inCapture)
		{
			inCapture = true;
			takingAlready = true;

			try
			{
				if (ModePreference.compareTo("0") == 0)
					requestID = CameraController.captureImagesWithParams(1, CameraController.YUV, new int[0],
							new int[0], true);
				else
					requestID = CameraController.captureImagesWithParams(1, CameraController.JPEG, new int[0],
							new int[0], true);
			} catch (Exception e)
			{
				e.printStackTrace();
				Log.d("Standard capture", "takePicture exception: " + e.getMessage());
				takingAlready = false;
				PluginManager.getInstance()
						.sendMessage(PluginManager.MSG_BROADCAST, PluginManager.MSG_CONTROL_UNLOCKED);
				MainScreen.getGUIManager().lockControls = false;
			}
		}

	}

	@Override
	public void onImageTaken(int frame, byte[] frameData, int frame_len, boolean isYUV)
	{
		PluginManager.getInstance().addToSharedMem("frame1" + SessionID, String.valueOf(frame));
		PluginManager.getInstance().addToSharedMem("framelen1" + SessionID, String.valueOf(frame_len));
		PluginManager.getInstance().addToSharedMem("frameorientation1" + SessionID,
				String.valueOf(MainScreen.getGUIManager().getDisplayOrientation()));
		PluginManager.getInstance().addToSharedMem("framemirrored1" + SessionID,
				String.valueOf(CameraController.isFrontCamera()));

		PluginManager.getInstance().addToSharedMem("amountofcapturedframes" + SessionID, "1");

		PluginManager.getInstance().addToSharedMem("isyuv" + SessionID, String.valueOf(isYUV));
		PluginManager.getInstance().addToSharedMem("isdroprocessing" + SessionID, ModePreference);

//		try
//		{
//			CameraController.startCameraPreview();
//		} catch (RuntimeException e)
//		{
//			Log.e("Capture", "StartPreview fail");
//		}

		PluginManager.getInstance().sendMessage(PluginManager.MSG_CAPTURE_FINISHED, String.valueOf(SessionID));

		takingAlready = false;
		inCapture = false;
	}

	@TargetApi(21)
	@Override
	public void onCaptureCompleted(CaptureResult result)
	{
		if (result.getSequenceId() == requestID)
		{
			PluginManager.getInstance().addToSharedMemExifTagsFromCaptureResult(result, SessionID);
		}
	}

	@Override
	public void onAutoFocus(boolean paramBoolean)
	{
//		Log.d("CapurePlugin", "onAutoFocus. takingAlready = " + takingAlready);
		if (takingAlready)
			takePicture();
	}

	@Override
	public void onPreviewFrame(byte[] data)
	{
	}

	public boolean delayedCaptureSupported()
	{
		return true;
	}
}

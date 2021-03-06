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

package com.almalence.plugins.capture.night;

import java.nio.FloatBuffer;
import java.util.Date;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.hardware.camera2.CaptureResult;
import android.opengl.GLES10;
import android.opengl.GLU;
import android.os.Build;
import android.os.Message;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Toast;

/* <!-- +++
 import com.almalence.opencam_plus.cameracontroller.CameraController;
 import com.almalence.opencam_plus.CameraParameters;
 import com.almalence.opencam_plus.MainScreen;
 import com.almalence.opencam_plus.PluginCapture;
 import com.almalence.opencam_plus.PluginManager;
 import com.almalence.opencam_plus.R;
 import com.almalence.opencam_plus.ui.GUI.CameraParameter;
 +++ --> */
// <!-- -+-
import com.almalence.opencam.CameraParameters;
import com.almalence.opencam.MainScreen;
import com.almalence.opencam.PluginCapture;
import com.almalence.opencam.PluginManager;
import com.almalence.opencam.cameracontroller.CameraController;
import com.almalence.opencam.ui.GUI.CameraParameter;
import com.almalence.opencam.R;
//-+- -->

import com.almalence.util.ImageConversion;

/***
 * Implements night capture plugin - different capture logics available
 ***/

public class NightCapturePlugin extends PluginCapture
{
	private static final int	HI_RES_FRAMES			= 8;

	private static Toast		capturingDialog;

	// almashot - related
	private boolean				aboutToTakePicture		= false;

	// shared between activities
	private static int			total_frames;

	// Night vision variables
	private GLCameraPreview		cameraPreview;
	private byte[]				data1;
	private byte[]				data2;
	private byte[]				dataS;
	private byte[]				dataRotated;

	int							onDrawFrameCounter		= 1;
	int[]						cameraTexture;
	
	// size of a texture must be a power of 2
	byte[]						glCameraFrame			= new byte[256 * 256];
	
	FloatBuffer					cubeBuff;
	FloatBuffer					texBuff;

	private byte[]				yuvData;

	float						currHalfWidth;
	float						currHalfHeight;

	float						cameraDist;
	int                         sensorGain;

	// preferences
	private static String		FocusPreference;
	private static boolean		OpenGLPreference = false;

	private int					preferenceSceneMode;
	private int					preferenceFocusMode;
	private int					preferenceFlashMode;

	private static String		nightVisionLayerShowPref;
	private static String		nightCaptureFocusPref;

	public NightCapturePlugin()
	{
		super("com.almalence.plugins.nightcapture",
				R.xml.preferences_capture_night,
				R.xml.preferences_capture_night_more,
				R.drawable.plugin_capture_night_nightvision_on,
				MainScreen.getAppResources().getString(R.string.NightVisionOn));
	}

	@Override
	public void onCreate()
	{
		if (!CameraController.isUseHALv3())
		{
			cameraPreview = new GLCameraPreview(MainScreen.getMainContext());
		}
		
		nightVisionLayerShowPref = MainScreen.getAppResources().getString(R.string.NightVisionLayerShow);
		nightCaptureFocusPref = MainScreen.getAppResources().getString(R.string.NightCaptureFocusPref);
	}

	@Override
	public void onStart()
	{
		getPrefs();

		if (OpenGLPreference)
		{
			quickControlIconID = R.drawable.plugin_capture_night_nightvision_on;
			quickControlTitle = MainScreen.getAppResources().getString(R.string.NightVisionOn);
		} else
		{
			quickControlIconID = R.drawable.plugin_capture_night_nightvision_off;
			quickControlTitle = MainScreen.getAppResources().getString(R.string.NightVisionOff);
		}
		
		if (CameraController.isUseHALv3())
		{
			quickControlIconID = -1;
		}
	}

	@Override
	public void onResume()
	{
		takingAlready = false;
		inCapture = false;

		MainScreen.getInstance().muteShutter(false);

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		preferenceSceneMode = prefs.getInt(MainScreen.sSceneModePref, MainScreen.sDefaultValue);
		preferenceFocusMode = prefs.getInt(CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref
				: MainScreen.sFrontFocusModePref, MainScreen.sDefaultFocusValue);
		preferenceFlashMode = prefs.getInt(MainScreen.sFlashModePref, MainScreen.sDefaultFlashValue);

		MainScreen.setCaptureYUVFrames(true);
	}

	@Override
	public void onPause()
	{
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		prefs.edit().putInt(MainScreen.sSceneModePref, preferenceSceneMode).commit();
		prefs.edit()
				.putInt(CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref
						: MainScreen.sFrontFocusModePref, preferenceFocusMode).commit();
		prefs.edit().putInt(MainScreen.sFlashModePref, preferenceFlashMode).commit();
	}

	@Override
	public void onGUICreate()
	{
		MainScreen.getInstance().disableCameraParameter(CameraParameter.CAMERA_PARAMETER_SCENE, true, false);
		MainScreen.getInstance().disableCameraParameter(CameraParameter.CAMERA_PARAMETER_FOCUS, true, false);
		MainScreen.getInstance().disableCameraParameter(CameraParameter.CAMERA_PARAMETER_FLASH, true, true);
	}
	
	@Override
	public boolean isGLSurfaceNeeded()
	{
		if (!CameraController.isUseHALv3())
			return true;
		else
			return false;
	}

	@Override
	public void onQuickControlClick()
	{
		Message msg = new Message();

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		SharedPreferences.Editor editor = prefs.edit();

		if (quickControlIconID == R.drawable.plugin_capture_night_nightvision_on)
		{
			quickControlIconID = R.drawable.plugin_capture_night_nightvision_off;
			quickControlTitle = MainScreen.getAppResources().getString(R.string.NightVisionOff);

			editor.putBoolean(nightVisionLayerShowPref, false);
			editor.commit();

			OpenGLPreference = false;

			data1 = null;
			data2 = null;
			dataS = null;
			dataRotated = null;
			yuvData = null;

			msg.what = PluginManager.MSG_OPENGL_LAYER_HIDE;
		} else if (quickControlIconID == R.drawable.plugin_capture_night_nightvision_off)
		{
			quickControlIconID = R.drawable.plugin_capture_night_nightvision_on;
			quickControlTitle = MainScreen.getAppResources().getString(R.string.NightVisionOn);

			editor.putBoolean(nightVisionLayerShowPref, true);
			editor.commit();

			OpenGLPreference = true;

			msg.what = PluginManager.MSG_OPENGL_LAYER_SHOW;
		}

		MainScreen.getMessageHandler().sendMessage(msg);
	}

	@SuppressLint("CommitPrefEdits")
	private void getPrefs()
	{
		String defaultFocus = "0";

		// Get the xml/preferences.xml preferences
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		FocusPreference = prefs.getString(nightCaptureFocusPref, defaultFocus);
		if (!CameraController.isUseHALv3())
			OpenGLPreference = prefs.getBoolean(nightVisionLayerShowPref, true);
	}

	@Override
	public void onDefaultsSelect()
	{
		String defaultFocus = "0";

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		FocusPreference = prefs.getString(nightCaptureFocusPref, defaultFocus);
	}

	@Override
	public void onShowPreferences()
	{
		String defaultFocus = "0";

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
		FocusPreference = prefs.getString(nightCaptureFocusPref, defaultFocus);
	}

	@Override
	public void selectImageDimension()
	{
		//max size will be used in supermode
		int captureIndex = 0;
		if (!CameraController.isUseHALv3())
			captureIndex = MainScreen.selectImageDimensionMultishot();
		
		int imgCaptureWidth = CameraController.MultishotResolutionsSizeList.get(captureIndex).getWidth();
		int imgCaptureHeight = CameraController.MultishotResolutionsSizeList.get(captureIndex).getHeight();

		MainScreen.setSaveImageWidth(imgCaptureWidth);
		MainScreen.setSaveImageHeight(imgCaptureHeight);

		MainScreen.setImageWidth(imgCaptureWidth);
		MainScreen.setImageHeight(imgCaptureHeight);

		Log.i("NightCapturePlugin", "NightCapturePlugin.setCameraImageSize SX = " +
				MainScreen.getImageWidth() + " SY = " + MainScreen.getImageHeight());
	}
	
	@Override
	public void setCameraPreviewSize()
	{
		List<CameraController.Size> cs = CameraController.getInstance().getSupportedPreviewSizes();

		CameraController.Size os = getOptimalPreviewSize(cs, MainScreen.getImageWidth(), MainScreen.getImageHeight());
		CameraController.getInstance().setCameraPreviewSize(os);
		MainScreen.setPreviewWidth(os.getWidth());
		MainScreen.setPreviewHeight(os.getHeight());
	}

	@Override
	public void setCameraPictureSize()
	{
		CameraController.getInstance().setPictureSize(MainScreen.getImageWidth(), MainScreen.getImageHeight());
		
		// ensure image data coming as intact as possible
		CameraController.getInstance().setJpegQuality(100);

		int[] sceneModes = CameraController.getInstance().getSupportedSceneModes();
		if (sceneModes != null && CameraController.isModeAvailable(sceneModes, CameraParameters.SCENE_MODE_NIGHT)
				&& (!Build.MODEL.contains("Nexus")))
		{
			CameraController.getInstance().setCameraSceneMode(CameraParameters.SCENE_MODE_NIGHT);

			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
			SharedPreferences.Editor editor = prefs.edit();
			editor.putInt(MainScreen.sSceneModePref, CameraParameters.SCENE_MODE_NIGHT);
			editor.commit();
		}

		try
		{
			int[] focusModes = CameraController.getInstance().getSupportedFocusModes();
			if (focusModes != null)
			{
				SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
				SharedPreferences.Editor editor = prefs.edit();

				if (FocusPreference.compareTo("0") == 0)
				{
					if (CameraController.isModeAvailable(focusModes, CameraParameters.AF_MODE_FIXED))
					{
						// should set to hyperfocal distance as per android doc
						CameraController.getInstance().setCameraFocusMode(CameraParameters.AF_MODE_FIXED);
						editor.putInt(CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref
								: MainScreen.sFrontFocusModePref, CameraParameters.AF_MODE_FIXED);
					} else if (CameraController.isModeAvailable(focusModes, CameraParameters.AF_MODE_AUTO))
					{
						CameraController.getInstance().setCameraFocusMode(CameraParameters.AF_MODE_AUTO);
						editor.putInt(CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref
								: MainScreen.sFrontFocusModePref, CameraParameters.AF_MODE_AUTO);
						editor.putString(nightCaptureFocusPref, "1");
						FocusPreference = "1";
					}
				} else if (CameraController.isModeAvailable(focusModes, CameraParameters.AF_MODE_AUTO))
				{
					CameraController.getInstance().setCameraFocusMode(CameraParameters.AF_MODE_AUTO);
					editor.putInt(CameraController.isFrontCamera() ? MainScreen.sRearFocusModePref
							: MainScreen.sFrontFocusModePref, CameraParameters.AF_MODE_AUTO);
				}

				PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext()).edit()
						.putInt(MainScreen.sSceneModePref, CameraController.getInstance().getSceneMode()).commit();
			}

			Log.i("NightCapturePlugin", "MainScreen.setupCamera setFocusMode success");
		} catch (RuntimeException e)
		{
			Log.e("NightCapturePlugin", "MainScreen.setupCamera unable to setFocusMode");
		}

		try
		{
			int[] flashModes = CameraController.getInstance().getSupportedFlashModes();
			if (flashModes != null)
			{
				CameraController.getInstance().setCameraSceneMode(CameraParameters.SCENE_MODE_AUTO);
				CameraController.getInstance().setCameraFlashMode(CameraParameters.FLASH_MODE_OFF);

				SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getMainContext());
				SharedPreferences.Editor editor = prefs.edit();
				editor.putInt("FlashModeValue", CameraParameters.FLASH_MODE_OFF);
				editor.commit();
			}
		} catch (RuntimeException e)
		{
			Log.e("CameraTest", "MainScreen.setupCamera unable to setFlashMode");
		}

		Message msg = new Message();
		if (OpenGLPreference)
			msg.what = PluginManager.MSG_OPENGL_LAYER_SHOW;
		else
			msg.what = PluginManager.MSG_OPENGL_LAYER_HIDE;
		MainScreen.getMessageHandler().sendMessage(msg);
	}

	@Override
	public void onPreferenceCreate(PreferenceActivity prefActivity)
	{
		final PreferenceActivity mPref = prefActivity;

		Preference fp = prefActivity.findPreference(nightCaptureFocusPref);
		if (fp != null)
		{
			fp.setOnPreferenceChangeListener(new OnPreferenceChangeListener()
			{
				public boolean onPreferenceChange(Preference preference, Object focus_new)
				{
					int new_value = Integer.parseInt(focus_new.toString());
					if ((new_value == 0)
							&& CameraController.getInstance().getSupportedFocusModes() != null
							&& !CameraController.isModeAvailable(CameraController.getInstance()
									.getSupportedFocusModes(), CameraParameters.AF_MODE_FIXED))
					{
						new AlertDialog.Builder(mPref).setIcon(R.drawable.gui_almalence_alert_dialog_icon)
								.setTitle(R.string.Pref_NightCapture_FocusModeAlert_Title)
								.setMessage(R.string.Pref_NightCapture_FocusModeAlert_Msg)
								.setPositiveButton(android.R.string.ok, null).create().show();

						((ListPreference) preference).setValue("1");
						return false;
					}
					return true;
				}
			});
		}
	}

	@Override
	public void onPreferenceCreate(PreferenceFragment prefActivity)
	{
		final PreferenceFragment mPref = prefActivity;

		Preference fp = prefActivity.findPreference(nightCaptureFocusPref);
		if (fp != null)
		{
			fp.setOnPreferenceChangeListener(new OnPreferenceChangeListener()
			{
				public boolean onPreferenceChange(Preference preference, Object focus_new)
				{
					int new_value = Integer.parseInt(focus_new.toString());
					if ((new_value == 0)
							&& CameraController.getInstance().getSupportedFocusModes() != null
							&& !CameraController.isModeAvailable(CameraController.getInstance()
									.getSupportedFocusModes(), CameraParameters.AF_MODE_FIXED))
					{
						new AlertDialog.Builder(mPref.getActivity())
								.setIcon(R.drawable.gui_almalence_alert_dialog_icon)
								.setTitle(R.string.Pref_NightCapture_FocusModeAlert_Title)
								.setMessage(R.string.Pref_NightCapture_FocusModeAlert_Msg)
								.setPositiveButton(android.R.string.ok, null).create().show();

						((ListPreference) preference).setValue("1");
						return false;
					}
					return true;
				}
			});
		}
	}

	public boolean delayedCaptureSupported()
	{
		return true;
	}

	@Override
	public void onShutterClick()
	{
		if (!takingAlready)
			startCaptureSequence();
	}

	private void startCaptureSequence()
	{
		MainScreen.getInstance().muteShutter(true);

		if (!inCapture)
		{
			Date curDate = new Date();
			SessionID = curDate.getTime();

			inCapture = true;
			takingAlready = false;

			LinearLayout bottom_layout = (LinearLayout) MainScreen.getInstance().findViewById(R.id.mainButtons);

			if (!CameraController.isUseHALv3())
			{
				capturingDialog = Toast.makeText(MainScreen.getInstance(), R.string.hold_still, Toast.LENGTH_SHORT);
				capturingDialog.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, bottom_layout.getHeight());
				capturingDialog.show();
			}

			// reiniting for every shutter press
			imagesTaken = 0;
			total_frames = HI_RES_FRAMES;

			if (FocusPreference.compareTo("0") == 0)
			{
				captureFrames();
				takingAlready = true;
			} else
			{
				int focusState = CameraController.getFocusState();
				int focusMode = CameraController.getInstance().getFocusMode();
				if ( (focusState == CameraController.FOCUS_STATE_IDLE ||
						focusState == CameraController.FOCUS_STATE_FOCUSING)
						&& focusMode != -1
						&& !(focusMode == CameraParameters.AF_MODE_CONTINUOUS_PICTURE
								|| focusMode == CameraParameters.AF_MODE_CONTINUOUS_VIDEO
								|| focusMode == CameraParameters.AF_MODE_INFINITY
								|| focusMode == CameraParameters.AF_MODE_FIXED
								|| focusMode == CameraParameters.AF_MODE_EDOF)
						&& !MainScreen.getAutoFocusLock())
					aboutToTakePicture = true;
				else if (focusMode != -1
						&& (focusMode == CameraParameters.AF_MODE_CONTINUOUS_PICTURE
						||  focusMode == CameraParameters.AF_MODE_CONTINUOUS_VIDEO))
				{
					captureFrames();
					takingAlready = true;
				}
				else if(!takingAlready && focusState == CameraController.FOCUS_STATE_FOCUSED)
				{
					captureFrames();
					takingAlready = true;
				}
				else
				{
					inCapture = false;

					PluginManager.getInstance().sendMessage(PluginManager.MSG_BROADCAST,
							PluginManager.MSG_CONTROL_UNLOCKED);

					MainScreen.getGUIManager().lockControls = false;
				}
			}
		}
	}

	@Override
	public void onImageTaken(int frame, byte[] frameData, int frame_len, boolean isYUV)
	{
		PluginManager.getInstance().addToSharedMem("frame" + (imagesTaken + 1) + SessionID,
				String.valueOf(frame));
		PluginManager.getInstance().addToSharedMem("framelen" + (imagesTaken + 1) + SessionID,
				String.valueOf(frame_len));

		// ToDo: there is no need to pass orientation for every frame, just for the first one
		// also, amountofcapturedframes can be set only once to total_frames
		PluginManager.getInstance().addToSharedMem("frameorientation" + (imagesTaken + 1) + SessionID,
				String.valueOf(MainScreen.getGUIManager().getDisplayOrientation()));
		PluginManager.getInstance().addToSharedMem("framemirrored" + (imagesTaken + 1) + SessionID,
				String.valueOf(CameraController.isFrontCamera()));
		PluginManager.getInstance().addToSharedMem("amountofcapturedframes" + SessionID,
				String.valueOf(imagesTaken + 1));

		if (imagesTaken == 0)
		{
			boolean isHALv3 = CameraController.isUseHALv3();
			PluginManager.getInstance().addToSharedMem("isHALv3" + SessionID,
					String.valueOf(isHALv3));
	
			// Note: a more memory-effective way would be to crop zoomed images right here
			// (only possible with HALv3)
			float zoom = CameraController.getInstance().getZoom();
			PluginManager.getInstance().addToSharedMem("zoom" + SessionID,
					String.valueOf(zoom));
			
			// pass sensor gain to the image processing functions if it is known
			PluginManager.getInstance().addToSharedMem("sensorGain" + SessionID,
					String.valueOf(sensorGain));
		}
		
//		try
//		{
//			CameraController.startCameraPreview();
//		} catch (RuntimeException e)
//		{
//			PluginManager.getInstance().sendMessage(PluginManager.MSG_CAPTURE_FINISHED, String.valueOf(SessionID));
//
//			imagesTaken = 0;
//			MainScreen.getInstance().muteShutter(false);
//			takingAlready = false;
//			return;
//		}

		if (++imagesTaken == total_frames)
		{
			PluginManager.getInstance().sendMessage(PluginManager.MSG_CAPTURE_FINISHED, String.valueOf(SessionID));

			takingAlready = false;
			inCapture = false;
		}

	}

	@TargetApi(21)
	@Override
	public void onCaptureCompleted(CaptureResult result)
	{
		sensorGain = result.get(CaptureResult.SENSOR_SENSITIVITY);

		if (result.getSequenceId() == requestID && imagesTaken == 0)
			PluginManager.getInstance().addToSharedMemExifTagsFromCaptureResult(result, SessionID);
	}
	
	@Override
	public void onExportFinished()
	{
		
	}

	public void captureFrames()
	{
		// we do not know sensor gain initially
		// 0 - means auto-detection will be performed in processing functions
		sensorGain = 0;
		requestID = CameraController.captureImagesWithParams(
				total_frames, CameraController.YUV_RAW, new int[0], null, true);
	}

	// onPreviewFrame is used to collect frames for brightened VF output
	@Override
	public void onPreviewFrame(byte[] data)
	{
		if (!CameraController.isUseHALv3())
		{
			if (OpenGLPreference && !inCapture)
			{
				if (data1 == null)
					data1 = data;
				else if (data2 == null)
				{
					data2 = data;
	
					if (dataS == null)
						dataS = new byte[data2.length];
					else if (dataS.length < data2.length)
						dataS = new byte[data2.length];
	
					int imageWidth = MainScreen.getPreviewWidth();
					int imageHeight = MainScreen.getPreviewHeight();
					
					ImageConversion.sumByteArraysNV21(data1, data2, dataS, imageWidth, imageHeight);
					if (CameraController.isFrontCamera())
					{
						dataRotated = new byte[dataS.length];
						ImageConversion.TransformNV21(dataS, dataRotated, imageWidth, imageHeight, 1, 0, 0);
	
						yuvData = dataRotated;
					} else
						yuvData = dataS;
									
					data1 = data2;
					data2 = null;
				}
			} else if (inCapture && data1 != null)
			{
				data1 = null;
				data2 = null;
				dataS = null;
			}
		}
	}

	@Override
	public void onAutoFocus(boolean paramBoolean)
	{
		if (inCapture) // disregard autofocus success (paramBoolean)
		{
			if (aboutToTakePicture)
			{
				captureFrames();
				takingAlready = true;
			}

			aboutToTakePicture = false;
		}
	}

	/******************************************************************************************************
	 * OpenGL layer functions
	 ******************************************************************************************************/
	
	@Override
	public void onGLSurfaceCreated(GL10 gl, EGLConfig config)
	{
		if (!CameraController.isUseHALv3())
		{
			cameraPreview.generateGLTexture(gl);
			gl.glEnable(GL10.GL_TEXTURE_2D); // Enable Texture Mapping ( NEW )
			gl.glShadeModel(GL10.GL_SMOOTH); // Enable Smooth Shading
			gl.glLineWidth(4.0f);
			gl.glClearColor(0.0f, 0.0f, 0.0f, 0.5f); // Black Background
			gl.glClearDepthf(1.0f); // Depth Buffer Setup
			gl.glEnable(GL10.GL_DEPTH_TEST); // Enables Depth Testing
			gl.glDepthFunc(GL10.GL_LEQUAL); // The Type Of Depth Testing To Do
	
			gl.glBlendFunc(GL10.GL_SRC_ALPHA, GLES10.GL_ONE);
	
			// Really Nice Perspective Calculations
			gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_NICEST);
		}
	}

	@Override
	public void onGLSurfaceChanged(GL10 gl, int width, int height)
	{
		if (!CameraController.isUseHALv3())
		{
			if (height == 0)
			{ // Prevent A Divide By Zero By
				height = 1; // Making Height Equal One
			}
	
			currHalfWidth = width / 2;
			currHalfHeight = height / 2;
	
			cameraDist = (float) (currHalfHeight / Math.tan(Math.toRadians(45.0f / 2.0f)));
	
			gl.glViewport(0, 0, width, height); // Reset The Current Viewport
			gl.glMatrixMode(GL10.GL_PROJECTION); // Select The Projection Matrix
			gl.glLoadIdentity(); // Reset The Projection Matrix
	
			// Calculate The Aspect Ratio Of The Window
			GLU.gluPerspective(gl, 45.0f, (float) width / (float) height, cameraDist / 10.0f, cameraDist * 10.0f);
	
			gl.glMatrixMode(GL10.GL_MODELVIEW); // Select The Modelview Matrix
			gl.glLoadIdentity(); // Reset The Modelview Matrix
	
			cameraPreview.setSurfaceSize(width, height);
		}
	}

	@Override
	public void onGLDrawFrame(GL10 gl)
	{
		if (!CameraController.isUseHALv3())
		{
			// Clear Screen And Depth Buffer
			gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
			gl.glLoadIdentity();
	
			// Drawing
			gl.glTranslatef(0.0f, 0.0f, -(cameraDist)); // Move 5 units into the
														// screen
			gl.glRotatef(-90, 0.0f, 0.0f, 1.0f); // Z
	
			if (OpenGLPreference && !inCapture && yuvData != null)
				synchronized (this)
				{
					try
					{
						// Draw the square
						cameraPreview.draw(gl, yuvData, MainScreen.getMainContext());
					} catch (RuntimeException e)
					{
						Log.e("onGLDrawFrame", "onGLDrawFrame in Night some exception" + e.getMessage());
					}
				}
		}
	}
}

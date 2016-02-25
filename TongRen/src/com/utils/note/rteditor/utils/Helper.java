/*
 * Copyright (C) 2015 Emanuel Moecklin
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

package com.utils.note.rteditor.utils;

import java.io.Closeable;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.utils.note.rteditor.api.RTApi;

/**
 * Miscellaneous helper methods
 */
public abstract class Helper {

    private static float sDensity = Float.MAX_VALUE;
    private static float sDensity4Fonts = Float.MAX_VALUE;

    public static void closeQuietly(Closeable closeable) {
        RTIOUtils.closeQuietly(closeable);
    }

    public static float getDisplayDensity() {
        synchronized (Helper.class) {
            if (sDensity == Float.MAX_VALUE) {
                sDensity = getDisplayMetrics().density;
            }
            return sDensity;
        }
    }

    public static int[] getScreenSize(){
        DisplayMetrics displayMetrics = getDisplayMetrics();
        return new int[]{displayMetrics.widthPixels,displayMetrics.heightPixels};
    }

    /**
     * Convert absolute pixels to scale dependent pixels.
     * This scales the size by scale dependent screen density (accessibility setting) and
     * the global display setting for message composition fields
     */
    public static int convertPxToSp(int pxSize) {
        return Math.round((float) pxSize * getDisplayDensity4Fonts());
    }

    /**
     * Convert scale dependent pixels to absolute pixels.
     * This scales the size by scale dependent screen density (accessibility setting) and
     * the global display setting for message composition fields
     */
    public static int convertSpToPx(int spSize) {
        return Math.round((float) spSize / getDisplayDensity4Fonts());
    }

    public static int dip2px(float dpValue) {
        final float scale = getDisplayDensity();
        return (int) (dpValue * scale + 0.5f);
    }

    public static int px2dip(float pxValue) {
        final float scale = getDisplayDensity();
        return (int) (pxValue / scale + 0.5f);
    }

    private static DisplayMetrics getDisplayMetrics() {
        Display display = ((WindowManager) RTApi.getApplicationContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        return metrics;
    }

    private static float getDisplayDensity4Fonts() {
        synchronized (Helper.class) {
            if (sDensity4Fonts == Float.MAX_VALUE) {
                sDensity4Fonts = getDisplayMetrics().density * getFontScale();
            }
            return sDensity4Fonts;
        }
    }

    private static float getFontScale() {
        Configuration config = RTApi.getApplicationContext().getResources().getConfiguration();
        return config.fontScale;
    }

}
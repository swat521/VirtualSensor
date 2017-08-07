package fr.frazew.virtualgyroscope.hooks;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.SparseArray;
import android.util.SparseIntArray;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import fr.frazew.virtualgyroscope.Util;
import fr.frazew.virtualgyroscope.VirtualSensorListener;
import fr.frazew.virtualgyroscope.XposedMod;

public class SensorChange {
    private static final float[] GRAVITY = new float[] {0F, 0F, 9.81F};
    private static final float NS2S = 1.0f / 1000000000.0f;

    // Filter stuff @TODO
    private float lastFilterValues[][] = new float[3][10];
    private float prevValues[] = new float[3];

    //Sensor values
    private float[] magneticValues = new float[3];
    private float[] accelerometerValues = new float[3];

    //Keeping track of the previous rotation matrix and timestamp
    private float[] prevRotationMatrix = new float[9];
    private long prevTimestamp = 0;

    public float[] handleListener(Sensor s, VirtualSensorListener listener, float[] values, int accuracy, long timestamp) {
        if (s.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (Util.checkSensorResolution(this.accelerometerValues, values, XposedMod.ACCELEROMETER_ACCURACY)) {
                this.accelerometerValues = values;
            }
            if (listener.getSensor() != null) {
                return getSensorValues(listener, timestamp);
            }

        } else if (s.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            if (Util.checkSensorResolution(this.magneticValues, values, XposedMod.MAGNETIC_ACCURACY)) {
                this.magneticValues = values;
            }
        }

        return null;
    }

    private float[] getSensorValues(VirtualSensorListener listener, long timestamp) {
        float[] values = new float[3];
        listener.sensorRef = null; // Avoid sending completely wrong values to the wrong sensor

        if (listener.isDummyGyroListener || listener.getSensor().getType() == Sensor.TYPE_GYROSCOPE) {
            float timeDifference = Math.abs((float) (timestamp - this.prevTimestamp) * NS2S);
            if (timeDifference != 0.0F) {
                values = this.getGyroscopeValues(timeDifference);

                if (Float.isNaN(values[0]) || Float.isInfinite(values[0]))
                    XposedBridge.log("VirtualSensor: Value #" + 0 + " is NaN or Infinity, this should not happen");

                if (Float.isNaN(values[1]) || Float.isInfinite(values[1]))
                    XposedBridge.log("VirtualSensor: Value #" + 1 + " is NaN or Infinity, this should not happen");

                if (Float.isNaN(values[2]) || Float.isInfinite(values[2]))
                    XposedBridge.log("VirtualSensor: Value #" + 2 + " is NaN or Infinity, this should not happen");
            }
            this.prevTimestamp = timestamp;
        } else if ((Build.VERSION.SDK_INT >= 19 && listener.getSensor().getType() == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) || listener.getSensor().getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] rotationMatrix = new float[9];
            SensorManager.getRotationMatrix(rotationMatrix, null, this.accelerometerValues, this.magneticValues);
            float[] quaternion = Util.rotationMatrixToQuaternion(rotationMatrix);

            values[0] = quaternion[1];
            values[1] = quaternion[2];
            values[2] = quaternion[3];
        } else if (listener.getSensor().getType() == Sensor.TYPE_GRAVITY) {
            float[] rotationMatrix = new float[9];

            SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magneticValues);
            values[0] = GRAVITY[0] * rotationMatrix[0] + GRAVITY[1] * rotationMatrix[3] + GRAVITY[2] * rotationMatrix[6];
            values[1] = GRAVITY[0] * rotationMatrix[1] + GRAVITY[1] * rotationMatrix[4] + GRAVITY[2] * rotationMatrix[7];
            values[2] = GRAVITY[0] * rotationMatrix[2] + GRAVITY[1] * rotationMatrix[5] + GRAVITY[2] * rotationMatrix[8];
        } else if (listener.getSensor().getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            float[] rotationMatrix = new float[9];

            SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magneticValues);
            values[0] = this.accelerometerValues[0] - (GRAVITY[0] * rotationMatrix[0] + GRAVITY[1] * rotationMatrix[3] + GRAVITY[2] * rotationMatrix[6]);
            values[1] = this.accelerometerValues[1] - (GRAVITY[0] * rotationMatrix[1] + GRAVITY[1] * rotationMatrix[4] + GRAVITY[2] * rotationMatrix[7]);
            values[2] = this.accelerometerValues[2] - (GRAVITY[0] * rotationMatrix[2] + GRAVITY[1] * rotationMatrix[5] + GRAVITY[2] * rotationMatrix[8]);
        }

        return values;
    }

    private float[] getGyroscopeValues(float timeDifference) {
        float[] angularRates = new float[] {0.0F, 0.0F, 0.0F};
        float[] rotationMatrix = new float[9];
        float[] gravityRot = new float[3];
        float[] angleChange = new float[3];

        SensorManager.getRotationMatrix(rotationMatrix, null, this.accelerometerValues, this.magneticValues);
        gravityRot[0] = GRAVITY[0] * rotationMatrix[0] + GRAVITY[1] * rotationMatrix[3] + GRAVITY[2] * rotationMatrix[6];
        gravityRot[1] = GRAVITY[0] * rotationMatrix[1] + GRAVITY[1] * rotationMatrix[4] + GRAVITY[2] * rotationMatrix[7];
        gravityRot[2] = GRAVITY[0] * rotationMatrix[2] + GRAVITY[1] * rotationMatrix[5] + GRAVITY[2] * rotationMatrix[8];
        SensorManager.getRotationMatrix(rotationMatrix, null, gravityRot, this.magneticValues);

        SensorManager.getAngleChange(angleChange, rotationMatrix, this.prevRotationMatrix);
        angularRates[0] = -(angleChange[1]) / timeDifference;
        angularRates[1] = (angleChange[2]) / timeDifference;
        angularRates[2] = (angleChange[0]) / timeDifference;

        return angularRates;
    }

    /*private static List<Object> filterValues(float[] values, float[][] lastFilterValues, float[] prevValues) {
        if (Float.isInfinite(values[0]) || Float.isNaN(values[0])) values[0] = prevValues[0];
        if (Float.isInfinite(values[1]) || Float.isNaN(values[1])) values[1] = prevValues[1];
        if (Float.isInfinite(values[2]) || Float.isNaN(values[2])) values[2] = prevValues[2];

        float[][] newLastFilterValues = new float[3][10];
        for (int i = 0; i < 3; i++) {
            // Apply lowpass on the value
            float alpha = 0.5F;
            float newValue = lowPass(alpha, values[i], prevValues[i]);
            //float newValue = values[i];

            for (int j = 0; j < 10; j++) {
                if (j == 0) continue;
                newLastFilterValues[i][j-1] = lastFilterValues[i][j];
            }
            newLastFilterValues[i][9] = newValue;

            float sum = 0F;
            for (int j = 0; j < 10; j++) {
                sum += lastFilterValues[i][j];
            }
            newValue = sum/10;

            //The gyroscope is moving even after lowpass
            if (newValue != 0.0F) {
                //We are under the declared resolution of the gyroscope, so the value should be 0
                if (Math.abs(newValue) < 0.01F) newValue = 0.0F;
            }

            prevValues[i] = values[i];
            values[i] = newValue;
        }

        List<Object> returnValue = new ArrayList<>();
        returnValue.add(values);
        returnValue.add(prevValues);
        returnValue.add(newLastFilterValues);
        return returnValue;
    }

    private static float lowPass(float alpha, float value, float prev) {
        return prev + alpha * (value - prev);
    }*/
}
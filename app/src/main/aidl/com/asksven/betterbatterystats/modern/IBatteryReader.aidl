package com.asksven.betterbatterystats.modern;

import android.os.ParcelFileDescriptor;

interface IBatteryReader {
    ParcelFileDescriptor readBatteryStats() = 0;
    void destroy() = 16777114;
}

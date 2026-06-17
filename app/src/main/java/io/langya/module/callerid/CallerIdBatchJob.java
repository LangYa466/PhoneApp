package io.langya.module.callerid;

import android.content.Context;

record CallerIdBatchJob(Context ctx, String number, CallerIdBatchListener listener) {}

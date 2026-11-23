package com.shengjian.player;

import android.content.Context;
import android.text.format.DateFormat;

import java.util.Date;

final class HistoryDisplay {
    static String summary(Context context, String verb, long count, long lastAt, boolean earlierCountUnknown) {
        String visits = count > 0 ? verb + (earlierCountUnknown ? "至少 " : " ") + count + " 次"
                : verb + "次数未记录";
        if (lastAt <= 0) return visits + " · 上次" + verb + "时间未记录";
        Date time = new Date(lastAt);
        return visits + " · 上次" + verb + " " + DateFormat.getDateFormat(context).format(time)
                + " " + DateFormat.getTimeFormat(context).format(time);
    }

    private HistoryDisplay() {}
}

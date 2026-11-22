package com.shengjian.player;

import java.io.IOException;

final class AnimeScheduleVerification {
    private AnimeScheduleVerification() {}

    static void verify() throws IOException {
        String body = "[{\"weekday\":{\"id\":3},\"items\":[{\"id\":123,\"name\":\"Original\",\"name_cn\":\"番剧\",\"type\":2},"
                + "{\"id\":123,\"name\":\"Duplicate\"},{\"id\":456,\"name\":\"Novel\",\"type\":1}]},"
                + "{\"weekday\":{\"id\":7},\"items\":[{\"id\":789,\"name\":\"Weekend\"}]}]";
        var schedule = AnimeSchedule.parse(body);
        if (schedule.size() != 7 || !schedule.get(0).isEmpty() || schedule.get(2).size() != 1
                || !schedule.get(2).get(0).title.equals("番剧") || !schedule.get(2).get(0).originalTitle.equals("Original")
                || !schedule.get(6).get(0).title.equals("Weekend")) throw new AssertionError("Bangumi weekly calendar");
        try {
            AnimeSchedule.parse("<html>Error</html>");
            throw new AssertionError("Calendar failure response must be reported");
        } catch (IOException expected) { }
        System.out.println("PASS Bangumi weekly calendar, title fallback, duplicate and malformed response");
    }
}

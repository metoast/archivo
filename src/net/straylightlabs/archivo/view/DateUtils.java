/*
 * Copyright 2015-2016 Todd Kulesza <todd@dropline.net>.
 *
 * This file is part of Archivo.
 *
 * Archivo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Archivo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Archivo.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.straylightlabs.archivo.view;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

class DateUtils {
    private final static DateTimeFormatter DATE_RECORDED_LONG_DATE_FORMATTER;
    private final static DateTimeFormatter DATE_RECORDED_SHORT_DATE_FORMATTER;
    private final static DateTimeFormatter DATE_RECORDED_TIME_FORMATTER;
    public final static DateTimeFormatter DATE_AIRED_FORMATTER;

    private static final int currentYear;
    private static final int today;
    private static final int yesterday;

    static {
        DATE_RECORDED_LONG_DATE_FORMATTER = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
        DATE_RECORDED_SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d");
        DATE_RECORDED_TIME_FORMATTER = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT);
        DATE_AIRED_FORMATTER = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
        currentYear = LocalDateTime.now().getYear();
        today = LocalDateTime.now().getDayOfYear();
        yesterday = LocalDateTime.now().minusDays(1).getDayOfYear();
    }

    public static String formatRecordedOnDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Recorded ");

        if (dateTime.getDayOfYear() == today) {
            sb.append("today");
        } else if (dateTime.getDayOfYear() == yesterday) {
            sb.append("yesterday");
        } else if (dateTime.getYear() == currentYear) {
            // Don't include the year for recordings from the current year
            sb.append(dateTime.format(DATE_RECORDED_SHORT_DATE_FORMATTER));
        } else {
            sb.append(dateTime.format(DATE_RECORDED_LONG_DATE_FORMATTER));
        }

        sb.append(" at ");
        sb.append(dateTime.format(DATE_RECORDED_TIME_FORMATTER));

        return sb.toString();
    }

    public static String formatRecordedOnDate(LocalDateTime dateTime) {
        if (dateTime.getDayOfYear() == today) {
            return "Today";
        } else if (dateTime.getDayOfYear() == yesterday) {
            return "Yesterday";
        } else if (dateTime.getYear() == currentYear) {
            // Don't include the year for recordings from the current year
            return dateTime.format(DATE_RECORDED_SHORT_DATE_FORMATTER);
        } else {
            return dateTime.format(DATE_RECORDED_LONG_DATE_FORMATTER);
        }
    }

    public static String formatArchivedOnDate(LocalDate date) {
        if (date.getDayOfYear() == today) {
            return "today";
        } else if (date.getDayOfYear() == yesterday) {
            return "yesterday";
        } else if (date.getYear() == currentYear) {
            // Don't include the year for recordings from the current year
            return "on " + date.format(DATE_RECORDED_SHORT_DATE_FORMATTER);
        } else {
            return "on " + date.format(DATE_RECORDED_LONG_DATE_FORMATTER);
        }
    }

    public static String formatDuration(Duration duration, boolean inProgress) {
        int hours = (int) duration.toHours();
        int minutes = (int) duration.toMinutes() - (hours * 60);
        int seconds = (int) (duration.getSeconds() % 60);

        // Round so that we're only displaying hours and minutes
        if (seconds >= 30) {
            minutes++;
        }
        if (minutes >= 60) {
            hours++;
            minutes = 0;
        }

        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(String.format("%d:%02d hour", hours, minutes));
            if (hours > 1 || minutes > 0)
                sb.append("s");
        } else {
            sb.append(String.format("%d minute", minutes));
            if (minutes != 1) {
                sb.append("s");
            }
        }
        if (inProgress)
            sb.append(" (still recording)");

        return sb.toString();
    }
}

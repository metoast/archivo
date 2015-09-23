/*
 * Copyright 2015 Todd Kulesza <todd@dropline.net>.
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

package net.straylightlabs.archivo.model;

import net.straylightlabs.archivo.Archivo;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model the Edit Decision List (EDL) format used by Comskip and MPlayer.
 * Includes methods for using EDL files with ffmpeg.
 */
public class EditDecisionList {
    private final List<Segment> segmentsToKeep;
    private final List<Segment> segmentsToCut;

    /**
     * Create a new EditDecisionList from a standard EDL file.
     */
    public static EditDecisionList createFromFile(Path input) throws IOException {
        List<Segment> toCut = parseEDLFile(input);
        return new EditDecisionList(toCut);
    }

    private static List<Segment> parseEDLFile(Path file) throws IOException {
        if (!Files.isReadable(file)) {
            throw new IOException("File is not readable");
        } else if (Files.isDirectory(file)) {
            throw new IOException("Path is a directory, not an EDL file");
        }

        List<Segment> segments = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                try {
                    Segment segment = Segment.fromString(line);
                    segments.add(segment);
                } catch (IllegalArgumentException e) {
                    Archivo.logger.error("Error parsing EDL line '{}': invalid format", line);
                }
            }
        }
        return segments;
    }

    private EditDecisionList(List<Segment> toCut) {
        segmentsToKeep = new ArrayList<>();
        segmentsToCut = toCut;
        buildKeepList();
    }

    private void buildKeepList() {
        double curTime = 0.0;
        for (Segment cutSegment : segmentsToCut) {
            double length = cutSegment.startTime - curTime;
            if (Double.compare(length, 0) > 0) {
                // Length is larger than 0
                segmentsToKeep.add(new Segment(curTime, cutSegment.startTime));
            }
            curTime = cutSegment.endTime;
        }
        segmentsToKeep.add(new Segment(curTime, Double.POSITIVE_INFINITY));
    }

    /**
     * Returns a list of time segments to keep.
     */
    public List<Segment> getSegmentsToKeep() {
        return Collections.unmodifiableList(segmentsToKeep);
    }

    @Override
    public String toString() {
        return "EditDecisionList{" +
                "segmentsToKeep=" + segmentsToKeep +
                ", segmentsToCut=" + segmentsToCut +
                '}';
    }

    public static class Segment {
        private final double startTime;
        private final double endTime;

        private static final Pattern EDL_LINE_PATTERN = Pattern.compile("^([\\d\\.]+)\\s+([\\d\\.]+)");

        /**
         * Parse a line from an EDL file.
         */
        public static Segment fromString(String line) {
            Matcher matcher = EDL_LINE_PATTERN.matcher(line);
            if (matcher.find()) {
                double start = Double.parseDouble(matcher.group(1));
                double end = Double.parseDouble(matcher.group(2));
                return new Segment(start, end);
            } else {
                throw new IllegalArgumentException("Input does not look like an EDL line");
            }
        }

        public Segment(double start, double end) {
            if (Double.compare(start, end) > 0) {
                throw new IllegalArgumentException("Start time cannot be later than end time");
            }
            startTime = start;
            endTime = end;
        }

        /**
         * Create a list representing the arguments ffmpeg will need to trim a video to this segment.
         */
        public List<String> buildFfmpegCutParamList() {
            List<String> params = new ArrayList<>();
            params.add("-ss");
            params.add(String.format("%1.2f", startTime));
            if (Double.isFinite(endTime - startTime)) {
                params.add("-t");
                params.add(String.format("%1.2f", endTime - startTime));
            }
            return params;
        }

        @Override
        public String toString() {
            return "Segment{" +
                    "startTime=" + startTime +
                    ", endTime=" + endTime +
                    '}';
        }
    }
}
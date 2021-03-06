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

package net.straylightlabs.archivo.net;

import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class MindTask extends Task<Void> {
    private final MindCommand command;
    private final MindRPC client;

    private final static Logger logger = LoggerFactory.getLogger(MindTask.class);

    public MindTask(MindRPC client, MindCommand command) {
        this.client = client;
        this.command = command;
    }

    @Override
    protected Void call() throws IOException {
        try {
            command.executeOn(client);
        } catch (IOException e) {
            logger.error("Error executing MindTask: ", e);
            throw e;
        }
        return null;
    }
}

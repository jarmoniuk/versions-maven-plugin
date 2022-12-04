package org.codehaus.mojo.versions.utils;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.function.Function;

import org.apache.maven.execution.MavenSession;
import org.codehaus.mojo.versions.api.Transport;

public class FileTransport implements Transport
{
    @Override
    public boolean isApplicable( URI uri )
    {
        return "file".equals( uri.getScheme() );
    }

    /**
     * Retrieves the resource indicated by the given uri.
     * @param uri uri pointing to the resource
     * @param serverId id of the server from which to download the information; may be {@code null}
     * @param mavenSession current Maven session; may be {@code null}
     * @param supplier function producing the desired object based on the input stream with the downloaded resource
     * @return input stream with the resource if the {@code uri} is a file resource; otherwise returns null
     * @throws IOException thrown if the I/O operation doesn't succeed
     */
    @Override
    public <T> T download( URI uri, String serverId, MavenSession mavenSession,
                           Function<InputStream, T> supplier ) throws IOException
    {
        try ( InputStream is = Files.newInputStream( Paths.get( uri ), StandardOpenOption.READ ) )
        {
            return supplier.apply( is );
        }

    }
}
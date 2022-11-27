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

import javax.inject.Named;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.settings.Proxy;
import org.codehaus.mojo.versions.api.Transport;
import sun.misc.IOUtils;

import static java.util.Optional.ofNullable;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.eclipse.aether.ConfigurationProperties.USER_AGENT;

@Named( "http" )
public class HttpTransport implements Transport
{
    /**
     * Retrieves the resource indicated by the given uri.
     * @param uri uri pointing to the resource
     * @param serverId id of the server from which to download the information; <em>may not</em> be {@code null}
     * @param mavenSession current Maven session; <em>may not</em> be {@code null}
     * @return input stream with the resource
     * @throws IOException thrown if the I/O operation doesn't succeed
     */
    @Override
    public InputStream download( URI uri, String serverId, MavenSession mavenSession ) throws IOException
    {
        assert serverId != null;
        assert mavenSession != null;

        HttpClientBuilder builder = HttpClientBuilder.create();
        builder.setUserAgent( mavenSession.getRepositorySession().getConfigProperties()
                .getOrDefault( USER_AGENT, "Maven" ).toString() );
        mavenSession.getSettings().getProxies()
                .stream()
                .filter( Proxy::isActive )
                .filter( proxy -> ofNullable( proxy.getProtocol() )
                        .map( p -> p.equals( uri.getScheme() ) )
                        .orElse( true ) )
                .filter( proxy -> ofNullable( proxy.getNonProxyHosts() )
                        .map( s -> s.split( "\\|" ) )
                        .map( a -> Arrays.stream( a )
                                .noneMatch( s -> s.equals( uri.getHost() ) ) )
                        .orElse( true ) )
                .findAny()
                .ifPresent( proxy ->
                {
                    builder.setProxy( new HttpHost( proxy.getProtocol(), proxy.getHost(), proxy.getPort() ) );
                    if ( !isBlank( proxy.getUsername() ) && !isBlank( proxy.getPassword() ) )
                    {
                        builder.setDefaultCredentialsProvider( new BasicCredentialsProvider()
                        {{
                            setCredentials( new AuthScope( proxy.getHost(), proxy.getPort() ),
                                    new UsernamePasswordCredentials( proxy.getUsername(),
                                            proxy.getPassword().toCharArray() ) );
                        }} );
                    }
                } );
        // TODO: add authentication, truststore, keystore, etc
        mavenSession.getSettings().getServers()
                .stream()
                .filter( s -> serverId.equals( s.getId() ) )
                .findFirst()
                .ifPresent( server ->
                {
                    return;
                } );
        mavenSession.getSettings().getMirrors()
                .stream()
                .filter( m -> serverId.equals( m.getMirrorOf() ) )
                .findFirst()
                .ifPresent( mirror ->
                {
                    // TODO: handle this
                    return;
                } );

        try ( CloseableHttpClient httpClient = builder.build() )
        {
            // copying to a new byte array so that the client and its underlying resources can be released
            return new ByteArrayInputStream(
                    IOUtils.readAllBytes( httpClient.execute( new HttpGet( uri ) ).getEntity().getContent() ) );
        }
    }
}

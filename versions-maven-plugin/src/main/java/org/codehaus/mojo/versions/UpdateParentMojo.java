package org.codehaus.mojo.versions;

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

import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.Segment;
import org.codehaus.mojo.versions.ordering.InvalidSegmentException;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;
import org.codehaus.mojo.versions.utils.DependencyBuilder;
import org.codehaus.mojo.versions.utils.PomHelper;
import org.codehaus.mojo.versions.utils.SegmentUtils;

import static org.apache.maven.shared.utils.StringUtils.isBlank;

/**
 * Sets the parent version to the latest parent version.
 *
 * @author Stephen Connolly
 * @since 1.0-alpha-1
 */
@Mojo( name = "update-parent", threadSafe = true )
public class UpdateParentMojo extends AbstractVersionsUpdaterMojo
{

    // ------------------------------ FIELDS ------------------------------

    /**
     * <p>If {@code skipResolution} is not set, specifies the <em>bottom</em> version considered
     * for target version resolution. If it is a version range, the resolved version will be
     * restricted by that range.</p>
     *
     * <p>If {@code skipResolution} is {@code true}, will specify the target version to which
     * the parent artifact will be updated.</p>
     * @since 1.0-alpha-1
     */
    @Parameter( property = "parentVersion" )
    protected String parentVersion = null;

    /**
     * to update parent version by force when it is RELEASE or LATEST
     *
     * @since 2.9
     */
    @Parameter( property = "forceUpdate", defaultValue = "false" )
    protected boolean forceUpdate = false;

    /**
     * Skips version resolution, only valid if {@code parentVersion} is set.
     * Will effectively set the new parent version to the one from {@code parentVersion}
     *
     * @since 2.13.0
     */
    @Parameter( property = "skipResolution", defaultValue = "false" )
    protected boolean skipResolution = false;

    /**
     * <p>Whether to downgrade a snapshot dependency if <code>allowSnapshots</code> is <code>false</code>
     * and there exists a version within the range fulfilling the criteria.</p>
     * <p>Default <code>false</code></p>
     *
     * @since 2.12.0
     */
    @Parameter( property = "allowDowngrade",
                defaultValue = "false" )
    protected boolean allowDowngrade;

    /**
     * Whether to allow the major version number to be changed.
     *
     * @since 2.13.0
     */
    @Parameter( property = "allowMajorUpdates", defaultValue = "true" )
    protected boolean allowMajorUpdates = true;

    /**
     * <p>Whether to allow the minor version number to be changed.</p>
     *
     * <p><b>Note: {@code false} also implies {@linkplain #allowMajorUpdates} {@code false}</b></p>
     *
     * @since 2.13.0
     */
    @Parameter( property = "allowMinorUpdates", defaultValue = "true" )
    protected boolean allowMinorUpdates = true;

    /**
     * <p>Whether to allow the incremental version number to be changed.</p>
     *
     * <p><b>Note: {@code false} also implies {@linkplain #allowMajorUpdates}
     * and {@linkplain #allowMinorUpdates} {@code false}</b></p>
     *
     * @since 2.13.0
     */
    @Parameter( property = "allowIncrementalUpdates", defaultValue = "true" )
    protected boolean allowIncrementalUpdates = true;

    // -------------------------- OTHER METHODS --------------------------

    @Inject
    public UpdateParentMojo( RepositorySystem repositorySystem,
                                MavenProjectBuilder projectBuilder,
                                ArtifactMetadataSource artifactMetadataSource,
                                WagonManager wagonManager,
                                ArtifactResolver artifactResolver )
    {
        super( repositorySystem, projectBuilder, artifactMetadataSource, wagonManager, artifactResolver );
    }

    @Override
    protected void update( ModifiedPomXMLEventReader pom )
            throws MojoExecutionException, MojoFailureException, XMLStreamException
    {
        if ( getProject().getParent() == null )
        {
            getLog().info( "Project does not have a parent" );
            return;
        }

        if ( reactorProjects.contains( getProject().getParent() ) )
        {
            getLog().info( "Project's parent is part of the reactor" );
            return;
        }

        if ( skipResolution && isBlank( parentVersion ) )
        {
            throw new MojoExecutionException( "skipResolution is only valid if parentVersion is set" );
        }

        String initialVersion = parentVersion == null
                ? getProject().getParent().getVersion()
                : parentVersion;
        try
        {
            ArtifactVersion artifactVersion = skipResolution
                    ? new DefaultArtifactVersion( parentVersion )
                    : resolveTargetVersion( initialVersion );
            if ( artifactVersion != null )
            {
                getLog().info( "Updating parent from " + getProject().getParent().getVersion()
                        + " to " + artifactVersion );

                if ( PomHelper.setProjectParentVersion( pom, artifactVersion.toString() ) )
                {
                    if ( getLog().isDebugEnabled() )
                    {
                        getLog().debug( "Made an update from " + getProject().getParent().getVersion()
                                + " to " + artifactVersion );
                    }
                    getChangeRecorder().recordUpdate( "updateParent", getProject().getParent().getGroupId(),
                            getProject().getParent().getArtifactId(), getProject().getParent().getVersion(),
                            artifactVersion.toString() );
                }
            }
        }
        catch ( InvalidVersionSpecificationException e )
        {
            throw new MojoExecutionException( "Invalid version range specification: " + initialVersion, e );
        }
        catch ( ArtifactMetadataRetrievalException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }
        catch ( InvalidSegmentException e )
        {
            throw new MojoExecutionException( "Invalid segment specification for version " + initialVersion, e );
        }
    }

    protected ArtifactVersion resolveTargetVersion( String initialVersion )
            throws MojoExecutionException, ArtifactMetadataRetrievalException, InvalidVersionSpecificationException,
            InvalidSegmentException
    {
        Artifact artifact = getHelper().createDependencyArtifact( DependencyBuilder
                .newBuilder()
                .withGroupId( getProject().getParent().getGroupId() )
                .withArtifactId( getProject().getParent().getArtifactId() )
                .withVersion( initialVersion )
                .withType( "pom" )
                .build() );

        VersionRange targetVersionRange = VersionRange.createFromVersionSpec( initialVersion );
        if ( targetVersionRange.getRecommendedVersion() != null )
        {
            targetVersionRange = targetVersionRange.restrict(
                    VersionRange.createFromVersionSpec( "[" + targetVersionRange.getRecommendedVersion() + ",)" ) );
        }

        final ArtifactVersions versions = getHelper().lookupArtifactVersions( artifact, false );
        Optional<Segment> unchangedSegment = SegmentUtils.determineUnchangedSegment( allowMajorUpdates,
                allowMinorUpdates, allowIncrementalUpdates, getLog() );

        // currentVersion (set to parentVersion here) is not included in the version range for searching upgrades
        // unless we set allowDowngrade to true
        for ( ArtifactVersion candidate : reverse( versions.getNewerVersions( initialVersion, unchangedSegment,
                allowSnapshots, !isBlank( parentVersion ) || allowDowngrade ) ) )
        {
            if ( allowDowngrade
                    || targetVersionRange == null
                    || ArtifactVersions.isVersionInRange( candidate, targetVersionRange ) )
            {
                if ( shouldApplyUpdate( artifact, getProject().getParent().getVersion(), candidate, forceUpdate ) )
                {
                    return candidate;
                }
                else
                {
                    getLog().debug( "Update not applied. Exiting." );
                    return null;
                }
            }
        }

        getLog().info( "No versions found" );
        return null;
    }

    private static <T> Iterable<T> reverse( T[] array )
    {
        return Arrays.stream( array ).sorted( Collections.reverseOrder() ).collect( Collectors.toList() );
    }
}

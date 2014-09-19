/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.aether;

import java.io.File;
import java.io.IOException;

import io.fabric8.common.util.Filter;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.resolution.ArtifactResolutionException;

public interface MavenResolver {

    File resolveFile(Artifact artifact) throws ArtifactResolutionException;

    DependencyTreeResult collectDependencies(PomDetails details, boolean offline, Filter<Dependency> excludeDependencyFilter) throws IOException, RepositoryException;

    DependencyTreeResult collectDependencies(VersionedDependencyId id, boolean offline, Filter<Dependency> excludeDependencyFilter) throws IOException, RepositoryException;

    PomDetails findPomFile(File fileJar) throws IOException;


    DependencyTreeResult collectDependenciesForJar(File artifactFile, boolean offline, Filter<Dependency> excludeFilter) throws RepositoryException, IOException;

    Artifact resolveArtifact(boolean offline, String groupId, String artifactId, String version, String classifier, String jar) throws ArtifactResolutionException;
}
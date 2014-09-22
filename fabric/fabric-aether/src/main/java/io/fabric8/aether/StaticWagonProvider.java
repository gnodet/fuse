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

import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.providers.file.FileWagon;
import org.apache.maven.wagon.providers.http.LightweightHttpWagon;
import org.apache.maven.wagon.providers.http.LightweightHttpWagonAuthenticator;
import org.apache.maven.wagon.providers.http.LightweightHttpsWagon;
import org.eclipse.aether.connector.wagon.WagonProvider;

public class StaticWagonProvider implements WagonProvider {
    private int timeout;

    public StaticWagonProvider() {
        this(10000);
    }

    public StaticWagonProvider(int timeout) {
        this.timeout = timeout;
    }

    public Wagon lookup(String roleHint) throws Exception {
        if ("file".equals(roleHint)) {
            return new FileWagon();
        }
        if ("http".equals(roleHint)) {
            LightweightHttpWagon wagon = new LightweightHttpWagon();
            wagon.setTimeout(timeout);
            wagon.setAuthenticator(new LightweightHttpWagonAuthenticator());
            return wagon;
        }
        if ("https".equals(roleHint)) {
            LightweightHttpsWagon wagon = new LightweightHttpsWagon();
            wagon.setTimeout(timeout);
            wagon.setAuthenticator(new LightweightHttpWagonAuthenticator());
            return wagon;
        }
        return null;
    }

    public void release(Wagon wagon) {
    }
}

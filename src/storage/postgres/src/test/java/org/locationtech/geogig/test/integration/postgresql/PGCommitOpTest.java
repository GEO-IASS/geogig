/* Copyright (c) 2015 Boundless.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/edl-v10.html
 *
 * Contributors:
 * Gabriel Roldan (Boundless) - initial implementation
 */
package org.locationtech.geogig.test.integration.postgresql;

import org.junit.Rule;
import org.locationtech.geogig.api.Context;
import org.locationtech.geogig.di.GeogigModule;
import org.locationtech.geogig.di.HintsModule;
import org.locationtech.geogig.repository.Hints;
import org.locationtech.geogig.storage.postgresql.PGStorageModule;
import org.locationtech.geogig.storage.postgresql.PGTemporaryTestConfig;
import org.locationtech.geogig.test.integration.CommitOpTest;

import com.google.inject.Guice;
import com.google.inject.util.Modules;

public class PGCommitOpTest extends CommitOpTest {

    @Rule
    public PGTemporaryTestConfig testConfig = new PGTemporaryTestConfig(getClass().getSimpleName());

    @Override
    protected Context createInjector() {

        String repoUrl = testConfig.getRepoURL();

        Hints hints = new Hints();
        hints.set(Hints.REPOSITORY_URL, repoUrl);
        return Guice.createInjector(
                Modules.override(new GeogigModule()).with(new HintsModule(hints),
                        new PGStorageModule())).getInstance(Context.class);
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URL;

/**
 * Fallback used by {@code mvnw} when neither curl nor wget are available.
 * Downloads the Maven Wrapper JAR from the URL passed as the first argument,
 * to the path passed as the second argument.
 *
 * <p>Authentication can be provided via the environment variables
 * {@code MVNW_USERNAME} and {@code MVNW_PASSWORD}.
 */
public final class MavenWrapperDownloader {

    private MavenWrapperDownloader() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println(" - ERROR: usage: java MavenWrapperDownloader <url> <output>");
            System.exit(1);
        }
        String url = args[0];
        String outputPath = args[1];

        String username = System.getenv("MVNW_USERNAME");
        String password = System.getenv("MVNW_PASSWORD");
        if (username != null && password != null) {
            final char[] pwd = password.toCharArray();
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, pwd);
                }
            });
        }

        URL website = new URL(url);
        try (InputStream in = website.openStream();
             FileOutputStream out = new FileOutputStream(outputPath)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}

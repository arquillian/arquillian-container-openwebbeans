/*
 * JBoss, Community-driven Open Source Middleware
 * Copyright 2010, JBoss by Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,  
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.container.openwebbeans.embedded_1;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;

import org.apache.webbeans.corespi.scanner.AbstractMetaDataDiscovery;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Filters;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.ArchiveAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;

/**
 * A ScannerService implementation that processes a ShrinkWrap bean archive
 *
 * <p>Arquillian supplies an in-memory ShrinkWrap archive which the test class
 * is to use to load classes and resources. This implementation of the
 * OpenWebBeans ScannerService SPI looks for the presence of a
 * /META-INF/beans.xml in the ShrinkWrap archive. If present, it registers the
 * location with the OpenWebBeans container, then proceeds to retrieve classes
 * from that archive and pass them to the AnnotationDB to be scanned and
 * processed as managed bean classes.</p>
 *
 * @author <a href="mailto:dan.allen@mojavelinux.com">Dan Allen</a>
 * @author <a href="mailto:struberg@apache.org">Mark Struberg</a>
 */
public class ShrinkWrapMetaDataDiscovery extends AbstractMetaDataDiscovery
{
    private Archive<?> mainArchive;

    public ShrinkWrapMetaDataDiscovery(Archive<?> mainArchive)
    {
        super();
        this.mainArchive = mainArchive;
    }

    @Override
    protected void configure()
    {
        scanArchive(mainArchive);
    }

    /**
     * Scan the given Archive. The following types are supported:
     *
     * <ul>
     *     <li>{@link JavaArchive}</li>
     *     <li>{@link WebArchive}</li>
     * </ul>
     *
     * We do not support EARs yet!
     *
     * @param archive to scan
     */
    private void scanArchive(Archive<?> archive)
    {
        boolean beansXmlPresent = false;

        if (archive instanceof WebArchive)
        {
            Map<ArchivePath, Node> beansXmls;
            beansXmls = archive.getContent(Filters.include("/WEB-INF/beans.xml"));
            beansXmlPresent |= parseBeansXmls(archive.getName(), beansXmls);

            // people might also add the marker file to WEB-INF/classes directly
            beansXmls = archive.getContent(Filters.include("/WEB-INF/classes/META-INF/beans.xml"));
            beansXmlPresent |= parseBeansXmls(archive.getName(), beansXmls);

            if (beansXmlPresent)
            {
                scanArchiveClasses(archive);
            }

            // and now we scan all contained JAR files from WEB-INF/lib
            Map<ArchivePath, Node> jarFiles = archive.getContent(Filters.include("/WEB-INF/lib/.*\\.jar"));
            for (Map.Entry<ArchivePath, Node> jarEntry : jarFiles.entrySet())
            {
                ArchiveAsset archiveAsset = (ArchiveAsset) jarEntry.getValue().getAsset();
                Archive jarArchive = (JavaArchive) archiveAsset.getArchive();
                scanArchive(jarArchive);
            }


        }
        else if (archive instanceof JavaArchive)
        {
            Map<ArchivePath, Node> beansXmls;
            beansXmls = archive.getContent(Filters.include("/META-INF/beans.xml"));
            beansXmlPresent = parseBeansXmls(archive.getName(), beansXmls);
            if (beansXmlPresent)
            {
                scanArchiveClasses(archive);
            }
        }
    }

    /**
     * Scan all the classes in the given Archive.
     * @param archive
     */
    private void scanArchiveClasses(Archive<?> archive)
    {
        Map<ArchivePath, Node> classes = archive.getContent(Filters.include(".*\\.class"));
        for (Map.Entry<ArchivePath, Node> classEntry : classes.entrySet())
        {
            try
            {
                getAnnotationDB().scanClass(classEntry.getValue().getAsset().openStream());
            }
            catch (Exception e)
            {
                throw new RuntimeException("Could not scan class", e);
            }
        }
    }

    /**
     * Take all given archives and add the bean.xml files to the
     * ScannerService.
     * @param archiveName is needed if multiple archives are used, e.g. in a WebArchive
     * @param beansXmls
     * @return <code>true</code> if at least one beans.xml has been parsed.
     */
    private boolean parseBeansXmls(String archiveName, Map<ArchivePath, Node> beansXmls)
    {
        boolean beansXmlPresent = false ;
        for (final Map.Entry<ArchivePath, Node> entry : beansXmls.entrySet())
        {
            try
            {
                String urlLocation = "archive://" + archiveName + entry.getKey().get();

                addWebBeansXmlLocation(
                        new URL(null, urlLocation, new URLStreamHandler()
                        {
                            @Override
                            protected URLConnection openConnection(URL u) throws IOException
                            {
                                return new URLConnection(u)
                                {
                                    @Override
                                    public void connect() throws IOException
                                    {}

                                    @Override
                                    public InputStream getInputStream() throws IOException
                                    {
                                        return entry.getValue().getAsset().openStream();
                                    }
                                };
                            };
                        }));
                beansXmlPresent = true;
            }
            catch (Exception e)
            {
                RuntimeException runtimeException;
                if (e instanceof RuntimeException)
                {
                    runtimeException = (RuntimeException) e;
                }
                else
                {
                    runtimeException = new RuntimeException("Error while parsing beans.xml location", e);
                }

                throw runtimeException;
            }
        }
        return beansXmlPresent;
    }
}

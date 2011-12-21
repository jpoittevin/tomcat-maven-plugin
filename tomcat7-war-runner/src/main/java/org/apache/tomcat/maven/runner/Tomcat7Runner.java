package org.apache.tomcat.maven.runner;
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Catalina;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.AccessLogValve;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.apache.tomcat.util.http.fileupload.IOUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

/**
 * FIXME add junit for that but when https://issues.apache.org/bugzilla/show_bug.cgi?id=52028 fixed
 * Main class used to run the standalone wars in a Apache Tomcat instance.
 *
 * @author Olivier Lamy
 * @since 2.0
 */
public class Tomcat7Runner
{
    // true/false to use the server.xml located in the jar /conf/server.xml
    public static final String USE_SERVER_XML_KEY = "useServerXml";

    // contains war name wars=foo.war,bar.war
    public static final String WARS_KEY = "wars";

    public static final String ENABLE_NAMING_KEY = "enableNaming";

    public static final String ACCESS_LOG_VALVE_FORMAT_KEY = "accessLogValveFormat";

    /**
     * key of the property which contains http protocol : HTTP/1.1 or org.apache.coyote.http11.Http11NioProtocol
     */
    public static final String HTTP_PROTOCOL_KEY = "connectorhttpProtocol";


    public int httpPort;

    public int httpsPort;

    public int ajpPort;

    public String serverXmlPath;

    public Properties runtimeProperties;

    public boolean resetExtract;

    public boolean debug = false;

    public boolean clientAuth = false;

    public String keyAlias = null;

    public String httpProtocol;

    public String extractDirectory = ".extract";

    public File extractDirectoryFile;

    public String loggerName;

    Catalina container;

    Tomcat tomcat;

    /**
     * key = context of the webapp, value = war path on file system
     */
    Map<String, String> webappWarPerContext = new HashMap<String, String>();

    public Tomcat7Runner()
    {
        // no op
    }

    public void run()
        throws Exception
    {

        PasswordUtil.deobfuscateSystemProps();

        if ( loggerName != null && loggerName.length() > 0 )
        {
            installLogger( loggerName );
        }

        this.extractDirectoryFile = new File( this.extractDirectory );

        debugMessage( "use extractDirectory:" + extractDirectoryFile.getPath() );

        // do we have to extract content
        if ( !extractDirectoryFile.exists() || resetExtract )
        {
            extract();
        }
        else
        {
            String wars = runtimeProperties.getProperty( WARS_KEY );
            populateWebAppWarPerContext( wars );
        }

        // create tomcat various paths
        new File( extractDirectory, "conf" ).mkdirs();
        new File( extractDirectory, "logs" ).mkdirs();
        new File( extractDirectory, "webapps" ).mkdirs();
        new File( extractDirectory, "work" ).mkdirs();
        File tmpDir = new File( extractDirectory, "temp" );
        tmpDir.mkdirs();

        System.setProperty( "java.io.tmpdir", tmpDir.getAbsolutePath() );

        System.setProperty( "catalina.base", extractDirectoryFile.getAbsolutePath() );
        System.setProperty( "catalina.home", extractDirectoryFile.getAbsolutePath() );

        // start with a server.xml
        if ( serverXmlPath != null || useServerXml() )
        {
            container = new Catalina();
            container.setUseNaming( this.enableNaming() );
            if ( serverXmlPath != null && new File( serverXmlPath ).exists() )
            {
                container.setConfig( serverXmlPath );
            }
            else
            {
                container.setConfig( new File( extractDirectory, "conf/server.xml" ).getAbsolutePath() );
            }
            container.start();
        }
        else
        {
            tomcat = new Tomcat();

            if ( this.enableNaming() )
            {
                System.setProperty( "catalina.useNaming", "true" );
                tomcat.enableNaming();
            }

            tomcat.getHost().setAppBase( new File( extractDirectory, "webapps" ).getAbsolutePath() );

            String connectorHttpProtocol = runtimeProperties.getProperty( HTTP_PROTOCOL_KEY );

            if ( httpProtocol != null && httpProtocol.trim().length() > 0 )
            {
                connectorHttpProtocol = httpProtocol;
            }

            debugMessage( "use connectorHttpProtocol:" + connectorHttpProtocol );

            if ( httpPort > 0 )
            {
                Connector connector = new Connector( connectorHttpProtocol );
                connector.setPort( httpPort );

                if ( httpsPort > 0 )
                {
                    connector.setRedirectPort( httpsPort );
                }
                // FIXME parameter for that def ? ISO-8859-1
                //connector.setURIEncoding(uriEncoding);

                tomcat.getService().addConnector( connector );

                tomcat.setConnector( connector );
            }

            // add a default acces log valve
            AccessLogValve alv = new AccessLogValve();
            alv.setDirectory( new File( extractDirectory, "logs" ).getAbsolutePath() );
            alv.setPattern( runtimeProperties.getProperty( Tomcat7Runner.ACCESS_LOG_VALVE_FORMAT_KEY ) );
            tomcat.getHost().getPipeline().addValve( alv );

            // create https connector
            if ( httpsPort > 0 )
            {
                Connector httpsConnector = new Connector( connectorHttpProtocol );
                httpsConnector.setPort( httpsPort );
                httpsConnector.setSecure( true );
                httpsConnector.setProperty( "SSLEnabled", "true" );
                httpsConnector.setProperty( "sslProtocol", "TLS" );

                String keystoreFile = System.getProperty( "javax.net.ssl.keyStore" );
                String keystorePass = System.getProperty( "javax.net.ssl.keyStorePassword" );
                String keystoreType = System.getProperty( "javax.net.ssl.keyStoreType", "jks" );

                if ( keystoreFile != null )
                {
                    httpsConnector.setAttribute( "keystoreFile", keystoreFile );
                }
                if ( keystorePass != null )
                {
                    httpsConnector.setAttribute( "keystorePass", keystorePass );
                }
                httpsConnector.setAttribute( "keystoreType", keystoreType );

                String truststoreFile = System.getProperty( "javax.net.ssl.trustStore" );
                String truststorePass = System.getProperty( "javax.net.ssl.trustStorePassword" );
                String truststoreType = System.getProperty( "javax.net.ssl.trustStoreType", "jks" );
                if ( truststoreFile != null )
                {
                    httpsConnector.setAttribute( "truststoreFile", truststoreFile );
                }
                if ( truststorePass != null )
                {
                    httpsConnector.setAttribute( "truststorePass", truststorePass );
                }
                httpsConnector.setAttribute( "truststoreType", truststoreType );

                httpsConnector.setAttribute( "clientAuth", clientAuth );
                httpsConnector.setAttribute( "keyAlias", keyAlias );

                tomcat.getService().addConnector( httpsConnector );

                if ( httpPort <= 0 )
                {
                    tomcat.setConnector( httpsConnector );
                }
            }

            // create ajp connector
            if ( ajpPort > 0 )
            {
                Connector ajpConnector = new Connector( "org.apache.coyote.ajp.AjpProtocol" );
                ajpConnector.setPort( ajpPort );
                // FIXME parameter for that def ? ISO-8859-1
                //ajpConnector.setURIEncoding(uriEncoding);
                tomcat.getService().addConnector( ajpConnector );
            }

            // add webapps
            for ( Map.Entry<String, String> entry : this.webappWarPerContext.entrySet() )
            {
                String baseDir = null;
                if ( entry.getKey().equals( "/" ) )
                {
                    baseDir = new File( extractDirectory, "webapps/ROOT.war" ).getAbsolutePath();
                }
                else
                {
                    baseDir = new File( extractDirectory, "webapps/" + entry.getValue() ).getAbsolutePath();
                }
                Context context = tomcat.addWebapp( entry.getKey(), baseDir );
                URL contextFileUrl = getContextXml( baseDir );
                if ( contextFileUrl != null )
                {
                    context.setConfigFile( contextFileUrl );
                }
            }

            tomcat.start();
        }

        waitIndefinitely();

    }

    private URL getContextXml( String warPath )
        throws IOException
    {
        InputStream inputStream = null;
        try
        {
            String urlStr = "jar:file:" + warPath + "!/META-INF/context.xml";
            debugMessage( "search context.xml in url:'" + urlStr + "'" );
            URL url = new URL( urlStr );
            inputStream = url.openConnection().getInputStream();
            if ( inputStream != null )
            {
                return url;
            }
        }
        catch ( FileNotFoundException e )
        {
            return null;
        }
        finally
        {
            IOUtils.closeQuietly( inputStream );
        }
        return null;
    }

    //protected WebappLoader createWebappLoader()

    private void waitIndefinitely()
    {
        Object lock = new Object();

        synchronized ( lock )
        {
            try
            {
                lock.wait();
            }
            catch ( InterruptedException exception )
            {
                System.exit( 1 );
            }
        }
    }

    public void stop()
        throws Exception
    {
        if ( container != null )
        {
            container.stop();
        }
        if ( tomcat != null )
        {
            tomcat.stop();
        }
    }

    protected void extract()
        throws Exception
    {

        if ( extractDirectoryFile.exists() )
        {
            FileUtils.deleteDirectory( extractDirectoryFile );
        }

        if ( !this.extractDirectoryFile.exists() )
        {
            boolean created = this.extractDirectoryFile.mkdirs();
            if ( !created )
            {
                System.out.println( "FATAL: impossible to create directory:" + this.extractDirectoryFile.getPath() );
                System.exit( 1 );
            }
        }

        // ensure webapp dir is here
        boolean created = new File( extractDirectory, "webapps" ).mkdirs();
        if ( !created )
        {
            System.out.println(
                "FATAL: impossible to create directory:" + this.extractDirectoryFile.getPath() + "/webapps" );
            System.exit( 1 );
        }

        String wars = runtimeProperties.getProperty( WARS_KEY );
        populateWebAppWarPerContext( wars );

        for ( Map.Entry<String, String> entry : webappWarPerContext.entrySet() )
        {
            debugMessage( "webappWarPerContext entry key/value: " + entry.getKey() + "/" + entry.getValue() );
            InputStream inputStream = null;
            try
            {
                inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream( entry.getValue() );
                if ( !useServerXml() )
                {
                    if ( entry.getKey().equals( "/" ) )
                    {
                        File expandFile = new File( extractDirectory, "webapps/ROOT.war" );
                        debugMessage( "expand to file:" + expandFile.getPath() );
                        expand( inputStream, expandFile );
                    }
                    else
                    {
                        File expandFile = new File( extractDirectory, "webapps/" + entry.getValue() );
                        debugMessage( "expand to file:" + expandFile.getPath() );
                        expand( inputStream, expandFile );
                    }
                }
                else
                {
                    File expandFile = new File( extractDirectory, "webapps/" + entry.getValue() );
                    debugMessage( "expand to file:" + expandFile.getPath() );
                    expand( inputStream, new File( extractDirectory, "webapps/" + entry.getValue() ) );
                }
            }
            finally
            {
                if ( inputStream != null )
                {
                    inputStream.close();
                }
            }
        }

        // expand tomcat configuration files if there
        expandConfigurationFile( "catalina.properties", extractDirectoryFile );
        expandConfigurationFile( "logging.properties", extractDirectoryFile );
        expandConfigurationFile( "tomcat-users.xml", extractDirectoryFile );
        expandConfigurationFile( "catalina.policy", extractDirectoryFile );
        expandConfigurationFile( "context.xml", extractDirectoryFile );
        expandConfigurationFile( "server.xml", extractDirectoryFile );
        expandConfigurationFile( "web.xml", extractDirectoryFile );

    }

    private static void expandConfigurationFile( String fileName, File extractDirectory )
        throws Exception
    {
        InputStream inputStream = null;
        try
        {
            inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream( "conf/" + fileName );
            if ( inputStream != null )
            {
                File confDirectory = new File( extractDirectory, "conf" );
                if ( !confDirectory.exists() )
                {
                    confDirectory.mkdirs();
                }
                expand( inputStream, new File( confDirectory, fileName ) );
            }
        }
        finally
        {
            if ( inputStream != null )
            {
                inputStream.close();
            }
        }

    }

    /**
     * @param warsValue we can value in format: wars=foo.war|contextpath;bar.war  ( |contextpath is optionnal if empty use the war name)
     *                  so here we return war file name and populate webappWarPerContext
     */
    private void populateWebAppWarPerContext( String warsValue )
    {
        StringTokenizer st = new StringTokenizer( warsValue, ";" );
        while ( st.hasMoreTokens() )
        {
            String warValue = st.nextToken();
            debugMessage( "populateWebAppWarPerContext warValue:" + warValue );
            String warFileName = "";
            String contextValue = "";
            int separatorIndex = warValue.indexOf( "|" );
            if ( separatorIndex >= 0 )
            {
                warFileName = warValue.substring( 0, separatorIndex );
                contextValue = warValue.substring( separatorIndex + 1, warValue.length() );

            }
            else
            {
                warFileName = contextValue;
            }
            debugMessage( "populateWebAppWarPerContext contextValue/warFileName:" + contextValue + "/" + warFileName );
            this.webappWarPerContext.put( contextValue, warFileName );
        }
    }


    /**
     * Expand the specified input stream into the specified file.
     *
     * @param input InputStream to be copied
     * @param file  The file to be created
     * @throws java.io.IOException if an input/output error occurs
     */
    private static void expand( InputStream input, File file )
        throws IOException
    {
        BufferedOutputStream output = null;
        try
        {
            output = new BufferedOutputStream( new FileOutputStream( file ) );
            byte buffer[] = new byte[2048];
            while ( true )
            {
                int n = input.read( buffer );
                if ( n <= 0 )
                {
                    break;
                }
                output.write( buffer, 0, n );
            }
        }
        finally
        {
            if ( output != null )
            {
                try
                {
                    output.close();
                }
                catch ( IOException e )
                {
                    // Ignore
                }
            }
        }
    }

    public boolean useServerXml()
    {
        return Boolean.parseBoolean( runtimeProperties.getProperty( USE_SERVER_XML_KEY, Boolean.FALSE.toString() ) );
    }


    public void debugMessage( String message )
    {
        if ( debug )
        {
            System.out.println( message );
        }
    }


    public boolean enableNaming()
    {
        return Boolean.parseBoolean( runtimeProperties.getProperty( ENABLE_NAMING_KEY, Boolean.FALSE.toString() ) );
    }

    private void installLogger( String loggerName )
        throws SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException,
        InvocationTargetException
    {
        if ( "slf4j".equals( loggerName ) )
        {

            try
            {
                // Check class is available
                final Class<?> clazz = Class.forName( "org.slf4j.bridge.SLF4JBridgeHandler" );

                // Remove all JUL handlers
                java.util.logging.LogManager.getLogManager().reset();

                // Install slf4j bridge handler
                final Method method = clazz.getMethod( "install", null );
                method.invoke( null );
            }
            catch ( ClassNotFoundException e )
            {
                System.out.println( "WARNING: issue configuring slf4j jul bridge, skip it" );
            }
        }
        else
        {
            System.out.println( "WARNING: loggerName " + loggerName + " not supported, skip it" );
        }
    }
}

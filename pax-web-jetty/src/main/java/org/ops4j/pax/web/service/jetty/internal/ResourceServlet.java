/* Copyright 2007 Alin Dreghiciu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.web.service.jetty.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.osgi.service.http.HttpContext;

class ResourceServlet
    extends HttpServlet
{

    //header constants
    private static final String IFNONEMATCH_HEADER = "If-None-Match";
    private static final String ETAG = "ETag";

    private final HttpContext m_httpContext;
    private final String m_contextName;
    private final String m_alias;
    private final String m_name;

    ResourceServlet( final HttpContext httpContext,
                     final String contextName,
                     final String alias,
                     final String name )
    {
        m_httpContext = httpContext;
        m_contextName = "/" + contextName;
        m_alias = alias;
        if( "/".equals( name ) )
        {
            m_name = "";
        }
        else
        {
            m_name = name;
        }
    }

    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
        throws ServletException, IOException
    {
        String mapping;
        Boolean included = request.getAttribute(Dispatcher.INCLUDE_REQUEST_URI) != null;
        if( included != null && included )
        {
            String servletPath = (String) request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH);
            String pathInfo = (String) request.getAttribute(Dispatcher.INCLUDE_PATH_INFO);
            if( servletPath == null )
            {
                servletPath = request.getServletPath();
                pathInfo = request.getPathInfo();
            }
            mapping = URIUtil.addPaths(servletPath,pathInfo);
        }
        else
        {
            included = Boolean.FALSE;
            if( m_contextName.equals( m_alias ) )
            {
                mapping = m_name + request.getRequestURI();
            }
            else
            {
                mapping = request.getRequestURI().replaceFirst( m_contextName, "/" );
                mapping = mapping.replaceFirst( m_alias, m_name );
            }
        }

        final URL url = m_httpContext.getResource( mapping );
        if( url == null )
        {
            response.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        final Resource resource = ResourceEx.newResource( url, false );
        try
        {
            if( !resource.exists() )
            {
                response.sendError( HttpServletResponse.SC_NOT_FOUND );
                return;
            }
            if( resource.isDirectory() )
            {
                response.sendError( HttpServletResponse.SC_FORBIDDEN );
                return;
            }

            //if the request contains an etag and its the same for the resource, we deliver a NOT MODIFIED response
            String eTag = String.valueOf(resource.lastModified());
            if ((request.getHeader(IFNONEMATCH_HEADER) != null) && (eTag.equals(request.getHeader(IFNONEMATCH_HEADER)))) {
                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                return;
            }
            //set the etag
            response.setHeader(ETAG, eTag);

            String mimeType = m_httpContext.getMimeType( mapping );
            if( mimeType == null )
            {
                try
                {
                    mimeType = url.openConnection().getContentType();
                }
                catch( IOException ignore )
                {
                    // we do not care about such an exception as the fact that we are using also the connection for
                    // finding the mime type is just a "nice to have" not an requirement
                }
            }
            if( mimeType != null )
            {
                response.setContentType( mimeType );
                // TODO shall we handle also content encoding?
            }

            OutputStream out = response.getOutputStream();
            if( out != null ) // null should be just in unit testing
            {
                if( out instanceof HttpConnection.Output )
                {
                    ( (HttpConnection.Output) out ).sendContent( resource.getInputStream() );
                }
                else
                {
                    // Write content normally
                    resource.writeTo( out, 0, resource.length() );
                }
            }
            response.setStatus( HttpServletResponse.SC_OK );
        }
        finally
        {
            resource.release();
        }
    }

    @Override
    public String toString()
    {
        return new StringBuilder()
            .append( this.getClass().getSimpleName() )
            .append( "{" )
            .append( "context=" ).append( m_contextName )
            .append( ",alias=" ).append( m_alias )
            .append( ",name=" ).append( m_name )
            .append( "}" )
            .toString();
    }

    public static abstract class ResourceEx extends Resource {

        private static final Method method;

        static {
            Method mth = null;
            try
            {
                mth = Resource.class.getDeclaredMethod( "newResource", URL.class, boolean.class );
                mth.setAccessible( true );
            } catch( Throwable t ) {
            }
            method = mth;
        }

        public static Resource newResource( URL url, boolean useCaches ) throws IOException
        {
            try {
                return (Resource) method.invoke( null, url, useCaches );
            } catch ( Throwable t ) {
                return Resource.newResource( url );
            }
        }
    }

}

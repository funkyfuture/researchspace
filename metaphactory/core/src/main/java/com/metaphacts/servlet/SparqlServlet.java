/*
 * Copyright (C) 2015-2017, metaphacts GmbH
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, you can receive a copy
 * of the GNU Lesser General Public License from http://www.gnu.org/
 */

package com.metaphacts.servlet;

import org.eclipse.rdf4j.common.io.IOUtil;
import org.eclipse.rdf4j.common.lang.FileFormat;

import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.Response.Status;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.eclipse.rdf4j.query.Operation;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.query.resultio.BooleanQueryResultFormat;
import org.eclipse.rdf4j.query.resultio.BooleanQueryResultWriter;
import org.eclipse.rdf4j.query.resultio.BooleanQueryResultWriterFactory;
import org.eclipse.rdf4j.query.resultio.BooleanQueryResultWriterRegistry;
import org.eclipse.rdf4j.query.resultio.QueryResultFormat;
import org.eclipse.rdf4j.query.resultio.QueryResultWriter;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultWriter;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultWriterFactory;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultWriterRegistry;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandler;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.RDFWriterFactory;
import org.eclipse.rdf4j.rio.RDFWriterRegistry;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.metaphacts.api.sparql.ServletRequestUtil;
import com.metaphacts.api.sparql.SparqlOperationBuilder;
import com.metaphacts.api.sparql.SparqlUtil;
import com.metaphacts.api.sparql.SparqlUtil.SparqlOperation;
import com.metaphacts.config.NamespaceRegistry;
import com.metaphacts.di.MainGuiceModule.MainTemplateProvider;
import com.metaphacts.repository.RepositoryManager;
import com.metaphacts.security.PermissionUtil;



/**
 * @author Johannes Trame <jt@metaphacts.com>
 */
public class SparqlServlet extends HttpServlet {

    private static final long serialVersionUID = 9086920765942724466L;

    private static final Logger logger = LogManager.getLogger(SparqlServlet.class);

    private static final Set<String> allRegisteredMimeTypes = getAllRegisteredWriterMimeTypes();

    @Inject
    private RepositoryManager repositoryManager;

    @Inject
    private MainTemplateProvider sparqlServletTemplate;


    @Inject
    private NamespaceRegistry nsRegistry;

    static class ContentType{
        static String FORM_URLENCODED = "application/x-www-form-urlencoded";
        static String SPARQL_QUERY = "application/sparql-query";
        static String SPARQL_UPDATE = "application/sparql-update";
        static String HTML = "text/html";
    }

    static class Parameter{
        static String REPOSITORY = "repository";
        static String QUERY = "query";
        static String UPDATE = "update";
    }

    /* (non-Javadoc)
     * @see javax.servlet.http.HttpServlet#doPost(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        ServletRequestUtil.traceLogRequest(req,logger);
        Optional<String> query = null;
        try{
            query = getOperationStringFromPost(req);
        }catch(IllegalArgumentException e){
            resp.sendError(Status.BAD_REQUEST.getStatusCode(), e.getMessage());
            return;
        }
        processOperation(query, req, resp);
    }

    /* (non-Javadoc)
     * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        ServletRequestUtil.traceLogRequest(req,logger);
        Optional<String> query = getQueryStringFromGet(req);
        processOperation(query, req, resp);
    }

    /**
     * Extracts "query" parameter from GET request. Parameter.UPDATE parameters can not
     * be send via GET and will be ignored.
     * <b>Package private for testing only</b>.
     * @param req
     * @return
     */
    Optional<String> getQueryStringFromGet(HttpServletRequest req){
        return Optional.ofNullable(req.getParameter(Parameter.QUERY));
    }

    Repository getRepositoryFromRequest(HttpServletRequest req){
        Optional<String> repID = Optional.ofNullable(req.getParameter(Parameter.REPOSITORY));
        return repositoryManager.getRepository(repID.orElse(RepositoryManager.DEFAULT_REPOSITORY_ID));
    }

    /**
     * Extracts the "query" or "update" parameter from different kind of POST
     * requests. Strongly follows the the specification <a
     * href="https://www.w3.org/TR/sparql11-protocol/#query-operation"
     * >https://www.w3.org/TR/sparql11-protocol/#query-operation</a> to check
     * for correct content-type. This means that query or update string will
     * only (only if) be returned if the content-type is set correctly.
     *
     * <ul>
     * <li>2.1.2 query via POST with URL-encoded parameters</li>
     * <li>2.2.1 update via POST with URL-encoded parameters</li>
     * <li>2.1.3 query via POST directly</li>
     * <li>2.2.2 update via POST directly</li>
     * </ul>
     *
     * <b>Package private for testing only</b>.
     *
     * @param req
     * @return
     * @throws IllegalArgumentException
     * @throws IOException
     */
    Optional<String> getOperationStringFromPost(HttpServletRequest req) throws IllegalArgumentException, IOException{
        Optional<String> contentType = ServletRequestUtil.getContentType(
                req,
                Optional.of(Lists.<String>newArrayList(ContentType.FORM_URLENCODED,ContentType.SPARQL_QUERY,ContentType.SPARQL_UPDATE ))
                );
        String contentTypeString = contentType.orElseThrow(
                () ->  new IllegalArgumentException("Null, emtpy or unkown content type. Valid content type must be set for POST request.")
        );
        /*
         * 2.1.2 query via POST with URL-encoded parameters
         * http://www.w3.org/TR/sparql11-protocol/#query-via-post-urlencoded
         *
         * In this case the content-type MUST be "application/x-www-form-urlencoded"
         */
        if(ContentType.FORM_URLENCODED.equalsIgnoreCase(contentTypeString))
            if(req.getParameter(Parameter.QUERY)!=null) return Optional.of(req.getParameter(Parameter.QUERY));

        /*
         * 2.2.1 update via POST with URL-encoded parameters
         * http://www.w3.org/TR/sparql11-protocol/#update-via-post-urlencoded
         *
         * In this case the content-type MUST be "application/x-www-form-urlencoded"
         */
        if(ContentType.FORM_URLENCODED.equalsIgnoreCase(contentTypeString))
            if(req.getParameter(Parameter.UPDATE)!=null) return Optional.of(req.getParameter(Parameter.UPDATE));

         /*
          * 2.1.3 query via POST directly
          * http://www.w3.org/TR/sparql11-protocol/#query-via-post-direct
          *
          * In this case the content-type MUST be "application/sparql-query"
          */
         if(ContentType.SPARQL_QUERY.equalsIgnoreCase(contentTypeString))
            return Optional.ofNullable(IOUtil.readString(req.getReader()));

         /*
          * 2.2.2 update via POST directly
          * http://www.w3.org/TR/sparql11-protocol/#update-via-post-direct
          */
         if(ContentType.SPARQL_UPDATE.equalsIgnoreCase(contentTypeString))
             return Optional.ofNullable(IOUtil.readString(req.getReader()));

         throw new IllegalArgumentException("Unsupported content type for POST request.");
    }

    protected void processOperation(Optional<String> query, HttpServletRequest req, HttpServletResponse resp) throws IOException{
        Optional<String> preferredMimeType = ServletRequestUtil.getPreferredMIMEType(allRegisteredMimeTypes, req);

        boolean requestsHtml = preferredMimeType.filter((String mime) -> mime.equalsIgnoreCase(ContentType.HTML)).isPresent();
        if (requestsHtml) {
            // return SPARQL editor interface if request is made by browser
            this.returnSparqlEditor(req, resp);
            return;
        } else if(query.isPresent() && requestsHtml && !req.getMethod().equalsIgnoreCase(HttpMethod.GET)){
            // return 400 if request mime type is html, query exists but request method is not GET
            resp.sendError(Status.BAD_REQUEST.getStatusCode(), "Parameter \"query\" must not be empty.");
        } else if(!query.isPresent() && !requestsHtml){
            // return 400 if requested mime type is not html and query is empty
            resp.sendError(Status.BAD_REQUEST.getStatusCode(), "Parameter \"query\" must not be empty.");
        }
        final String queryString = query.get();
        logger.debug("Received the following query string for execution:\n {} \n Hash Code: \"{}\" ", queryString, queryString.hashCode() );
        
        final String preferredMimeTypeString = preferredMimeType.orElse("");
        logger.trace("Detected mimetype \"{} \" for query with hash \"{}\".", preferredMimeTypeString, queryString.hashCode());

        try(RepositoryConnection con = getRepositoryFromRequest(req).getConnection()){
            Operation sparqlOperation = SparqlOperationBuilder.create(queryString).resolveUser(nsRegistry.getUserIRI()).build(con);
            SparqlOperation operationType = SparqlUtil.getOperationType(sparqlOperation);
            logger.trace("Query with hash \"{}\" is of type \"{}\"",queryString.hashCode(), operationType);

            /*
             * permission check
             */
            if(!PermissionUtil.hasSparqlPermission(operationType)){
                resp.sendError(Status.FORBIDDEN.getStatusCode(), "No permission to execute SPARQL Operation "+operationType.name());
                return;
            }

            FileFormat rdfFormat ;
            switch(operationType){
                case SELECT:{
                    TupleQueryResultWriterRegistry resultWriterRegistry = TupleQueryResultWriterRegistry.getInstance();
                    rdfFormat = resultWriterRegistry
                            .getFileFormatForMIMEType(preferredMimeTypeString)
                            .orElse(TupleQueryResultFormat.SPARQL);
                    Optional<TupleQueryResultWriterFactory> writerFactory = resultWriterRegistry.get((QueryResultFormat) rdfFormat);
                    TupleQueryResultWriter writer = writerFactory.get().getWriter(resp.getOutputStream());
                    addNamespaces(writer);
                    logger.debug("Evaluating query with hash \"{}\" as TupleQuery using \"{}\"", queryString.hashCode(), writer.getClass() );
                    setContentType(resp,rdfFormat);
                    ((TupleQuery) sparqlOperation).evaluate(writer);
                    return;
                }
                case DESCRIBE:
                case CONSTRUCT:{
                    RDFWriterRegistry resultWriterRegistry = RDFWriterRegistry.getInstance();
                    rdfFormat = resultWriterRegistry
                            .getFileFormatForMIMEType(preferredMimeTypeString)
                            .orElse(RDFFormat.TURTLE);
                    Optional<RDFWriterFactory> writerFactory = resultWriterRegistry.get((RDFFormat) rdfFormat);
                    RDFWriter writer = writerFactory.get().getWriter(resp.getOutputStream());
                    addNamespaces(writer);
                    logger.debug("Evaluating query with hash \"{}\" as GraphQuery using \"{}\"", queryString.hashCode(), writer.getClass() );
                    setContentType(resp,rdfFormat);
                    writer.startRDF();
                    try(GraphQueryResult result = ((GraphQuery) sparqlOperation).evaluate()){
                        while(result.hasNext()){
                            writer.handleStatement(result.next());
                        }
                    }
                    writer.endRDF();
                    return;
                }
                case ASK:{
                    BooleanQueryResultWriterRegistry resultWriterRegistry = BooleanQueryResultWriterRegistry.getInstance();
                    rdfFormat = resultWriterRegistry
                            .getFileFormatForMIMEType(preferredMimeTypeString)
                            .orElse(BooleanQueryResultFormat.SPARQL);
                    Optional<BooleanQueryResultWriterFactory> writerFactory = resultWriterRegistry.get((QueryResultFormat) rdfFormat);
                    BooleanQueryResultWriter writer = writerFactory.get().getWriter(resp.getOutputStream());
                    addNamespaces(writer);
                    logger.debug("Evaluating query with hash \"{}\" as BooleanQuery using \"{}\"", queryString.hashCode(), writer.getClass() );
                    boolean result = ((BooleanQuery) sparqlOperation).evaluate();
                    setContentType(resp,rdfFormat);
                    writer.handleBoolean(result);
                    return;
                }
                case UPDATE:{
                    logger.debug("Evaluating query with hash \"{}\" as UPDATE operation.", queryString.hashCode());
                    ((Update) sparqlOperation).execute();
                    resp.setStatus(Status.OK.getStatusCode());
                    return;
                }
                default:
                    throw new IllegalStateException("Unsupported operation!");
                }

        }catch(Exception e){
            resp.sendError(Status.INTERNAL_SERVER_ERROR.getStatusCode(), e.getMessage());
            return;
        }

    }

    /**
     * Sets "Content-Type" header for the servlet response.
     * @param resp
     * @param rdfFormat
     */
    private void setContentType(HttpServletResponse resp, FileFormat rdfFormat) {
        resp.setContentType(rdfFormat.getDefaultMIMEType()+";charset="+Charsets.UTF_8);
    }

    private void addNamespaces(QueryResultWriter writer) {
        /// TODO get and iterate of namespaces from registry
        //writer.handleNamespace(prefix, uri);
    }

    private void addNamespaces(RDFHandler handler) {
        /// TODO get and iterate of namespaces from registry
        //handler.handleNamespace(prefix, uri);
    }

    /**
     * Writes the template for the front-end SPARQL editor to the output stream.
     * @param req
     * @param resp
     * @throws IOException
     */
    protected void returnSparqlEditor(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType(ContentType.HTML);
        resp.setStatus(Status.OK.getStatusCode());
        resp.getOutputStream().write(sparqlServletTemplate.get().getBytes(Charsets.UTF_8));
    }

    /**
     * Returns a set of all mime types for all registered parsers including
     * text/html.
     *
     * @return
     */
    private static Set<String> getAllRegisteredWriterMimeTypes() {
        HashSet<String> all = Sets.newHashSet();
        all.addAll(SparqlUtil.getAllRegisteredWriterMimeTypes());
        all.add(ContentType.HTML);
        return all;
    }

}

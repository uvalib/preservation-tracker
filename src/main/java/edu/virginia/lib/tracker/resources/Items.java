package edu.virginia.lib.tracker.resources;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import edu.virginia.lib.aptrust.helper.FusekiReader;

@Path("item")
public class Items {
    
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String PRESERVATION_PACKAGE_TYPE = "http://fedora.lib.virginia.edu/preservation#PreservationPackage";
    private static final String EXTERNAL_SYSTEM = "http://fedora.lib.virginia.edu/preservation#externalSystem";
    private static final String DC_IDENTIFIER = "http://purl.org/dc/elements/1.1/identifier";
    private static final String DC_TITLE= "http://purl.org/dc/elements/1.1/title";
    private static final String VIRGO_VIEW = "http://fedora.lib.virginia.edu/preservation#hasVirgoView";
    
    private static final String REPOSITORY_ROOT = "http://fedora01.lib.virginia.edu:8080/fcrepo/rest/";

    protected FusekiReader triplestore = new FusekiReader("http://fedora01.lib.virginia.edu:8080/fuseki/fcrepo-before-crash");
    
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id: .*}")
    public JsonArray findItem(@PathParam("id") final String id) throws IOException {
        final JsonArrayBuilder a = Json.createArrayBuilder();
        for (String uri : lookupKnownId(id)) {
            a.add(getInfoSummary(uri));
        }
        for (String uri : lookupDCID(id)) {
            a.add(getInfoSummary(uri));
        }
        return a.build();
    }
    
    private List<String> lookupDCID(final String id) throws IOException {
        return triplestore.getQueryResponse("PREFIX dc: <http://purl.org/dc/elements/1.1/>\n" + 
                "\n" + 
                "SELECT ?s\n" + 
                "WHERE {\n" + 
                "  ?s dc:identifier '" + id + "' .\n" + 
                "}").stream().flatMap(m -> Stream.of(m.get("s"))).collect(Collectors.toList());
    }
    
    private List<String> lookupKnownId(final String id) throws IOException {
        if (triplestore.getFirstAndOnlyQueryResponse("ASK { <" + REPOSITORY_ROOT + id + "> ?p ?o }").get("_askResult").equals("true")) {
            return Collections.singletonList(REPOSITORY_ROOT + id);
        } else {
            return Collections.EMPTY_LIST;
        }
    }
    
    /**
     * Gets a summary of the information known about a resource that may be useful 
     * and relevant for tracking.
     * @throws IOException 
     */
    private JsonObject getInfoSummary(final String uri) throws IOException {
        JsonObjectBuilder o = Json.createObjectBuilder();
        o.add("uri", uri);
        boolean presPackage = false;
        JsonArrayBuilder ids = Json.createArrayBuilder();
        JsonArrayBuilder titles = Json.createArrayBuilder();
        JsonArrayBuilder views = Json.createArrayBuilder();
        final String genericQuery = "PREFIX dc: <http://purl.org/dc/elements/1.1/>\n" + 
                "PREFIX dcterms: <http://purl.org/dc/terms/>\n" + 
                "\n" + 
                "SELECT ?p ?o ?oid ?odesc\n" + 
                "WHERE {\n" + 
                "    <" + uri + "> ?p ?o\n" + 
                "    OPTIONAL { ?o dc:identifier ?oid }\n" + 
                "    OPTIONAL { ?o dcterms:description ?odesc }\n" + 
                "}";
        for (Map<String, String> result : triplestore.getQueryResponse(genericQuery)) {
            if (result.get("p").equals(RDF_TYPE)) {
                final String type = result.get("o");
                if (type.equals(PRESERVATION_PACKAGE_TYPE)) {
                    presPackage = true;
                }
            }
            if (result.get("p").equals(DC_IDENTIFIER)) {
                ids.add(result.get("o"));
            }
            if (result.get("p").equals(DC_TITLE)) {
                titles.add(result.get("o"));
            }
            if (result.get("p").equals(VIRGO_VIEW)) {
                views.add(result.get("o"));
            }
            if (result.get("p").equals(EXTERNAL_SYSTEM)) {
                o.add("system of record", result.get("odesc") + " (" + result.get("oid") + ")");
            }
        }
        JsonArray t = titles.build();
        if (t.size() > 0) {
            o.add("title", t);
        }
        JsonArray i = ids.build();
        if (i.size() > 0) {
            o.add("id", i);
        }
        JsonArray p = views.build();
        if (p.size() > 0) {
            o.add("published URLs", p);
        }
        o.add("APTrust-bound", presPackage);
        
        // include premis events
        JsonArrayBuilder events = Json.createArrayBuilder();
        final String eventQuery = "PREFIX premis: <http://www.loc.gov/premis/rdf/v1#>\n" + 
                "PREFIX pres: <http://fedora.lib.virginia.edu/preservation#>\n" + 
                "\n" + 
                "SELECT ?e ?type ?time ?bagSize ?bagPayloadSize ?outcome\n" + 
                "WHERE {\n" + 
                "    <" + uri + "> premis:hasEvent ?e .\n" + 
                "    ?e premis:hasEventType ?type .\n" + 
                "    ?e premis:hasEventDateTime ?time\n" + 
                "  OPTIONAL {\n" + 
                "    ?e pres:bagSize ?bagSize .\n" + 
                "    ?e pres:bagPayloadSize ?bagPayloadSize\n" + 
                "  }\n" + 
                "  OPTIONAL { \n" + 
                "    ?e premis:hasEventOutcomeInformation ?outcomeInfo .\n" + 
                "    ?outcomeInfo premis:hasEventOutcome ?outcome\n" + 
                "  }\n" + 
                "} ORDER BY ?time";
        for (Map<String, String> result : triplestore.getQueryResponse(eventQuery)) {
            JsonObjectBuilder event = Json.createObjectBuilder();
            event.add("type", result.get("type"));
            event.add("date", result.get("time"));
            event.add("bagSize", result.get("bagSize"));
            event.add("bagPayloadSize", result.get("bagPayloadSize"));
            if (result.get("outcome") != null) {
                event.add("outcome", result.get("outcome"));
            }
            events.add(event.build());
        }
        JsonArray e = events.build();
        if (e.size() > 0) { 
            o.add("events", e);
        }
        
        // include the nearby graph in a format for visjs
        final String graphQuery = "PREFIX premis: <http://www.loc.gov/premis/rdf/v1#>\n" + 
                "PREFIX pres: <http://fedora.lib.virginia.edu/preservation#>\n" + 
                "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" + 
                "PREFIX dc: <http://purl.org/dc/elements/1.1/>\n" + 
                "\n" + 
                "SELECT ?p ?o ?id ?title\n" + 
                "WHERE {\n" + 
                "    <" + uri + "> ?p ?o .\n" + 
                "    ?o rdf:type <http://fedora.info/definitions/v4/repository#Resource> .\n" + 
                "  OPTIONAL { ?o dc:title ?title }\n" + 
                "  OPTIONAL { ?o dc:identifier ?id }\n" +
                //"  MINUS { <" + uri + ">    \n" + 
                //"      <http://www.w3.org/ns/ldp#contains> ?o }" +
                "}";
        JsonArrayBuilder nodes = Json.createArrayBuilder();
        final Set<String> nodeIds = new HashSet<String>();
        JsonArrayBuilder edges = Json.createArrayBuilder();
        nodes.add(Json.createObjectBuilder().add("id", id(uri)).add("label", (t.size() > 0 ? t.get(0).toString() : (i.size() > 0 ? i.get(0).toString() : uri))).build());
        for (Map<String, String> result : triplestore.getQueryResponse(graphQuery)) {
            final String nUri = result.get("o");
            String label = result.get("title");
            if (label == null || label.length() == 0) {
                label = result.get("id");
            }
            if (label == null || label.length() == 0) {
                label = id(uri);
            }
            if (!nodeIds.contains(nUri)) {
                nodeIds.add(nUri);
                nodes.add(Json.createObjectBuilder().add("color",  "gray").add("id", id(nUri)).add("label", label).build());
            }
            edges.add(Json.createObjectBuilder().add("to", id(nUri)).add("from", id(uri)).add("label", prefix(result.get("p"))).build());
        }
        o.add("_graph_nodes", nodes.build());
        o.add("_graph_edges", edges.build());
        
        
        return o.build();
    }
    
    private String id(final String uri) {
        return uri.replace(REPOSITORY_ROOT, "");
    }
    
    private String prefix(final String uri) {
        return uri.replace("http://www.loc.gov/premis/rdf/v1#", "premis:").replace("http://www.w3.org/ns/ldp#", "ldp:").replace("http://fedora.info/definitions/v4/repository#", "fcrepo:").replace("http://purl.org/dc/terms/", "dc:terms").replace("http://fedora.lib.virginia.edu/preservation#", "pres:").replace("http://fedora.lib.virginia.edu/wsls/relationships#", "wsls:");        
    }
    
    

}

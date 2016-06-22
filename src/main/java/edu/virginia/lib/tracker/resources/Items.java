package edu.virginia.lib.tracker.resources;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    protected FusekiReader triplestore = new FusekiReader("http://fedora01.lib.virginia.edu:8080/fuseki/fcrepo-before-crash");
    
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id: [^/]*}")
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
        if (triplestore.getFirstAndOnlyQueryResponse("ASK { <http://fedora01.lib.virginia.edu:8080/fcrepo/rest/" + id + "> ?p ?o }").get("_askResult").equals("true")) {
            return Collections.singletonList("http://fedora01.lib.virginia.edu:8080/fcrepo/rest/" + id);
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
        o.add("title", titles.build());
        o.add("id", ids.build());
        o.add("published URLs", views.build());
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
        o.add("events", events.build());
        
        // TODO: include relevant graph... (test this with a WSLS script)
        
        return o.build();
    }
    
    

}

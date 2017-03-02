package org.aktin.broker.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

import javax.activation.DataSource;
import javax.xml.bind.JAXB;

import org.aktin.broker.client.BrokerClient.OutputWriter;
import org.aktin.broker.xml.NodeList;
import org.aktin.broker.xml.RequestInfo;
import org.aktin.broker.xml.RequestList;
import org.aktin.broker.xml.RequestStatusInfo;
import org.aktin.broker.xml.RequestStatusList;
import org.aktin.broker.xml.RequestTargetNodes;
import org.aktin.broker.xml.ResultInfo;
import org.aktin.broker.xml.ResultList;
import org.aktin.broker.xml.util.Util;
import org.w3c.dom.Node;

public class BrokerAdmin extends AbstractBrokerClient {

	public BrokerAdmin(URI brokerEndpoint) {
		super(brokerEndpoint);
	}
	@Override
	public void setEndpoint(URI brokerEndpoint){
		super.setEndpoint(brokerEndpoint);
		// TODO retrieve aggregator URL from broker info
		setAggregatorEndpoint(brokerEndpoint.resolve("/aggregator/"));		
	}
	@Override
	protected URI getQueryBaseURI() {
		return resolveBrokerURI("request/");
	}
	public Reader getRequestDefinition(int id, String mediaType) throws IOException{
		return getRequestDefinition(getQueryURI(id), mediaType);
	}
	private Reader getRequestDefinition(URI location, String mediaType) throws IOException{
		HttpURLConnection c = openConnection("GET", location);
		return contentReader(c, mediaType);	
		
	}
	// TODO return actual media type
	public Reader getRequestNodeMessage(int requestId, int nodeId) throws IOException{
		URI uri = getQueryURI(requestId);
		HttpURLConnection c = openConnection("GET", uri.resolve(uri.getPath()+"/status/"+nodeId));
		return contentReader(c, null);	
	}
	// TODO also return media type, e.g. via Datasource wrapping HttpURLConnection
	public NodeResource getNodeResource(int nodeId, String resourceId) throws IOException{
		HttpURLConnection c = openConnection("GET", "node/"+nodeId+"/"+URLEncoder.encode(resourceId,"UTF-8"));
		return wrapResource(c, resourceId);
	}
	public <T> T getNodeResourceJAXB(int nodeId, String resourceId, Class<T> type) throws IOException{
		NodeResource r = getNodeResource(nodeId, resourceId);
		try( InputStream in = r.getInputStream() ){
			// TODO verify content type xml
			return JAXB.unmarshal(in, type);
		}
	}
	public Properties getNodeProperties(int nodeId, String resourceId) throws IOException{
		Properties props;
		NodeResource r = getNodeResource(nodeId, resourceId);
		try( InputStream in = r.getInputStream() ){
			props = new Properties();
			props.loadFromXML(in);
		}
		return props;
	}
	public String getNodeString(int nodeId, String resourceId) throws IOException{
		NodeResource r = getNodeResource(nodeId, resourceId);
		// TODO parse and use charset from concent type
		try( Reader reader = new InputStreamReader(r.getInputStream()) ){
			return Util.readContent(reader);
		}
	}
	/**
	 * Create a request with specified content type and content
	 * @param contentType content type
	 * @param writer writer for the content
	 * @return request id
	 * @throws IOException IO error
	 */
	public int createRequest(String contentType, OutputWriter writer) throws IOException{
		HttpURLConnection c = openConnection("POST", "request");
		c.setDoOutput(true);
		c.setRequestProperty("Content-Type", contentType);
		try( OutputStream out = c.getOutputStream() ){
			writer.write(out);
		}
		c.getInputStream().close();
		String location = c.getHeaderField("Location");
		if( location == null ){
			throw new IOException("No location in response headers");
		}
		try {
			return getQueryId(new URI(location));
		} catch (URISyntaxException e) {
			throw new IOException("Response header location no valid URI", e);
		}
	}
	
	public int createRequest(String contentType, final InputStream content) throws IOException{
		return createRequest(contentType,  new OutputWriter(){
			@Override
			public void write(OutputStream dest) throws IOException {
				writeContent(dest, content);
			}	
		});
	}
	public int createRequest(String contentType, final Node content) throws IOException{
		return createRequest(contentType+";charset=UTF-8", new OutputWriter(){
			@Override
			public void write(OutputStream dest) throws IOException {
				writeContent(content, dest, "UTF-8");
			}	
		});
	}
	public int createRequest(String contentType, String content) throws IOException{
		return createRequest(contentType+";charset=UTF-8", new OutputWriter.ForString(content, "UTF-8"));
	}
	public void deleteRequest(int requestId) throws IOException{
		delete(getQueryURI(requestId));
	}
	private void putRequestDefinition(URI requestURI, String contentType, OutputWriter writer) throws IOException{
		HttpURLConnection c = openConnection("PUT", requestURI);
		c.setDoOutput(true);
		c.setRequestProperty("Content-Type", contentType);
		try( OutputStream out = c.getOutputStream() ){
			writer.write(out);
		}
		c.getInputStream().close();		
	}
	public void publishRequest(int requestId) throws IOException{
		URI uri = getQueryURI(requestId);
		HttpURLConnection c = openConnection("POST", uri.resolve(uri.getPath()+"/publish"));
		c.getInputStream().close();
	}
	public void closeRequest(int requestId) throws IOException{
		URI uri = getQueryURI(requestId);
		HttpURLConnection c = openConnection("POST", uri.resolve(uri.getPath()+"/close"));
		c.getInputStream().close();
	}
	public void putRequestDefinition(int requestId, String contentType, OutputWriter writer) throws IOException{
		putRequestDefinition(getQueryURI(requestId), contentType, writer);
	}
	public void putRequestDefinition(int requestId, String contentType, String content) throws IOException{
		putRequestDefinition(getQueryURI(requestId), contentType+";charset=UTF-8", new OutputWriter.ForString(content, "UTF-8"));
	}

	public List<org.aktin.broker.xml.Node> listNodes() throws IOException{
		HttpURLConnection c = openConnection("GET", "node");
		NodeList nl;
		try( InputStream response = c.getInputStream() ){
			nl = JAXB.unmarshal(response, NodeList.class);
		}
		if( nl.getNodes() == null ){
			return Collections.emptyList();
		}else{
			return nl.getNodes();
		}
	}
	public org.aktin.broker.xml.Node getNode(int nodeId) throws IOException{
		HttpURLConnection c = openConnection("GET", "node/"+nodeId);
		if( c.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND ){
			return null;
		}else try( InputStream response = c.getInputStream() ){
			return JAXB.unmarshal(response, org.aktin.broker.xml.Node.class);
		}
	}
	public List<RequestInfo> listAllRequests() throws IOException{
		HttpURLConnection c = openConnection("GET", "request");
		RequestList list = null;
		try( InputStream response = c.getInputStream() ){
			list = JAXB.unmarshal(response, RequestList.class);
		}
		return postprocessRequestList(list);
	}
	/**
	 * Retrieve request info. This info does not include the node status.
	 * 
	 * @param requestId request id
	 * @return request info
	 * @throws IOException communications error
	 */
	public RequestInfo getRequestInfo(int requestId) throws IOException{
		HttpURLConnection c = openConnection("OPTIONS", "request/"+requestId);
		if( c.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND ){
			return null;
		}else try( InputStream response = c.getInputStream() ){
			return JAXB.unmarshal(response, RequestInfo.class);
		}
	}
	public List<RequestStatusInfo> listRequestStatus(int requestId) throws IOException{
		HttpURLConnection c = openConnection("GET", "request/"+requestId+"/status");
		c.setRequestProperty("Content-Type", "application/xml");
		RequestStatusList list = null;
		try( InputStream response = c.getInputStream() ){
			list = JAXB.unmarshal(response, RequestStatusList.class);
		}
		if( list.getStatusList() == null ){
			return Collections.emptyList();
		}else{
			return list.getStatusList();
		}
	}

	public List<ResultInfo> listResults(int requestId) throws IOException{
		HttpURLConnection c = openConnection("GET", resolveAggregatorURI("request/"+requestId+"/result"));
		c.setRequestProperty("Content-Type", "application/xml");
		ResultList list = null;
		try( InputStream response = c.getInputStream() ){
			list = JAXB.unmarshal(response, ResultList.class);
		}
		if( list.getResults() == null ){
			return Collections.emptyList();
		}else{
			return list.getResults();
		}
	}
	// TODO ResultInfo getResultInfo(int requestId, String nodeId)
	// TODO remove Function<...> for Java 7 compatibility
	public <T> T getResult(int requestId, int nodeId, String acceptMediaType, Function<DataSource,T> unmarshaller) throws IOException{
		HttpURLConnection c = openConnection("GET", resolveAggregatorURI("request/"+requestId+"/result/"+nodeId));
		if( acceptMediaType != null ){
			c.setRequestProperty("Accept", acceptMediaType);
		}
		if( c.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND ){
			return null;
		}
		DataSource ds = new URLConnectionDataSource(c);
		try{
			return unmarshaller.apply(ds);
		}catch( UncheckedIOException e ){
			// unwrap
			throw e.getCause();
		}
	}

	public String getResultString(int requestId, int nodeId, String acceptMediaType) throws IOException{
		// TODO can we use Function in Java7?
		return getResult(requestId, nodeId, acceptMediaType, new Function<DataSource, String>() {

			@Override
			public String apply(DataSource ds) {
				// TODO charset
				try( InputStreamReader reader = new InputStreamReader(ds.getInputStream()) ){
					StringBuilder sb = new StringBuilder();
					char[] buf = new char[2048];
					while( true ){
						int len = reader.read(buf, 0, buf.length);
						if( len == -1 )break;
						sb.append(buf, 0, len);
					}
					return sb.toString();
				}catch( IOException e ){
					throw new UncheckedIOException(e);
				}
			}
		});
	}

	public int[] getRequestTargetNodes(int requestId) throws IOException{
		URI uri = getQueryURI(requestId);
		HttpURLConnection c = openConnection("GET", uri.resolve(uri.getPath()+"/nodes"));
		RequestTargetNodes nodes;
		try( InputStream response = c.getInputStream() ){
			nodes = JAXB.unmarshal(response, RequestTargetNodes.class);
		}// TODO catch file not found error
		if( nodes == null ){
			return null;
		}else{
			return nodes.getNodes();
		}
	}
	public void setRequestTargetNodes(int requestId, int[] nodes) throws IOException{
		URI uri = getQueryURI(requestId);
		HttpURLConnection c = openConnection("PUT", uri.resolve(uri.getPath()+"/nodes"));
		c.setDoOutput(true);
		RequestTargetNodes tn = new RequestTargetNodes(nodes);
		c.setRequestProperty("Content-Type", "application/xml");
		try( OutputStream out = c.getOutputStream() ){
			JAXB.marshal(tn, out);
		}
		c.getInputStream().close();		
	}
	public void clearRequestTargetNodes(int requestId) throws IOException{
		URI uri = getQueryURI(requestId);
		HttpURLConnection c = openConnection("DELETE", uri.resolve(uri.getPath()+"/nodes"));
		c.setDoOutput(false);
		c.getInputStream().close();		
	}
}

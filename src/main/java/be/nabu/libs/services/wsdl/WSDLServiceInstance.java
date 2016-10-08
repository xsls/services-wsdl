package be.nabu.libs.services.wsdl;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.text.ParseException;

import be.nabu.libs.authentication.api.principals.BasicPrincipal;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.HTTPClient;
import be.nabu.libs.http.client.NTLMPrincipalImpl;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.libs.resources.URIUtils;
import be.nabu.libs.services.api.ExecutionContext;
import be.nabu.libs.services.api.ServiceException;
import be.nabu.libs.services.api.ServiceInstance;
import be.nabu.libs.types.BaseTypeInstance;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.base.ComplexElementImpl;
import be.nabu.libs.types.base.ValueImpl;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.binding.xml.XMLBinding;
import be.nabu.libs.types.binding.xml.XMLMarshaller;
import be.nabu.libs.types.properties.AttributeQualifiedDefaultProperty;
import be.nabu.libs.types.properties.ElementQualifiedDefaultProperty;
import be.nabu.libs.types.properties.MinOccursProperty;
import be.nabu.libs.types.structure.Structure;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.LimitedReadableContainer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.mime.api.ContentPart;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.PlainMimeContentPart;

public class WSDLServiceInstance implements ServiceInstance {

	private WSDLService definition;
	
	public WSDLServiceInstance(WSDLService wsdlService) {
		this.definition = wsdlService;
	}

	@Override
	public WSDLService getDefinition() {
		return definition;
	}
	
	LimitedReadableContainer<ByteBuffer> buildInput(ComplexContent input, Charset charset) throws IOException {
		ComplexType buildRequestEnvelope = buildRequestEnvelope(true);
		ComplexContent envelope = buildRequestEnvelope.newInstance();
		envelope.set("Body", ((ComplexType) buildRequestEnvelope.get("Body").getType()).newInstance());
		if (input != null) {
			((ComplexContent) envelope.get("Body")).set(getDefinition().getOperation().getOperation().getInput().getParts().get(0).getElement().getName(), input);
		}
		// use marshaller directly to access more features
		XMLMarshaller marshaller = new XMLMarshaller(new BaseTypeInstance(buildRequestEnvelope));
		ByteBuffer buffer = IOUtils.newByteBuffer();
		marshaller.setAllowXSI(definition.isAllowXsi());
		marshaller.setAllowDefaultNamespace(definition.isAllowDefaultNamespace());
		if (definition.isAllowDefaultNamespace()) {
			// we set the default namespace to that of the actual input, not all parsers are too good with namespaces, having the main content
			// with as few prefixes as possible is always a good thing
			if (input != null) {
				marshaller.setDefaultNamespace(input.getType().getNamespace());
			}
			else {
				marshaller.setDefaultNamespace(getDefinition().getOperation().getDefinition().getTargetNamespace());
			}
		}
		boolean soapNamespaceFixed = false;
		if (getDefinition().getNamespaces() != null) {
			for (PredefinedNamespace namespace : getDefinition().getNamespaces()) {
				marshaller.setPrefix(namespace.getPrefix(), namespace.getNamespace());
				soapNamespaceFixed |= buildRequestEnvelope.getNamespace().equals(namespace.getNamespace());
			}
		}
		if (!soapNamespaceFixed) {
			// fix the soap prefix, otherwise it will be autogenerated as "tns1"
			// again: some parsers aren't too bright
			marshaller.setPrefix(buildRequestEnvelope.getNamespace(), "soap");
		}
		marshaller.marshal(IOUtils.toOutputStream(buffer), charset, envelope);
		return buffer;
	}
	
	ComplexContent parseOutput(ReadableContainer<ByteBuffer> input, Charset charset) throws IOException, ParseException {
		XMLBinding responseBinding = new XMLBinding(buildRequestEnvelope(false), charset);
		return responseBinding.unmarshal(IOUtils.toInputStream(input), new Window[0]);
	}

	@Override
	public ComplexContent execute(ExecutionContext executionContext, ComplexContent input) throws ServiceException {
		String endpoint = (String) input.get("endpoint");
		String transactionId = (String) input.get("transactionId");
		// if no endpoint is given, use the one from the wsdl
		if (endpoint == null && !getDefinition().getOperation().getDefinition().getServices().isEmpty() && !getDefinition().getOperation().getDefinition().getServices().get(0).getPorts().isEmpty()) {
			endpoint = getDefinition().getOperation().getDefinition().getServices().get(0).getPorts().get(0).getEndpoint();
		}
		if (endpoint == null) {
			throw new ServiceException("SOAP-1", "No endpoint passed in and none were found in the wsdl");
		}
		try {
			LimitedReadableContainer<ByteBuffer> buffer = buildInput((ComplexContent) input.get("request"), getDefinition().getCharset());
			
			final String username = input == null || input.get("authentication/username") == null ? definition.getUsername() : (String) input.get("authentication/username");
			final String password = input == null || input.get("authentication/password") == null ? definition.getPassword() : (String) input.get("authentication/password");

			BasicPrincipal principal = null;
			if (username != null) {
				int index = username.indexOf('/');
				if (index < 0) {
					index = username.indexOf('\\');
				}
				if (index < 0) {
					principal = new BasicPrincipal() {
						private static final long serialVersionUID = 1L;
						@Override
						public String getName() {
							return username;
						}
						@Override
						public String getPassword() {
							return password;
						}
					};
				}
				// create an NTLM principal
				else if (username != null) {
					principal = new NTLMPrincipalImpl(username.substring(0, index), username.substring(index + 1), password);
				}
			}
			
			URI uri = new URI(URIUtils.encodeURI(endpoint));
			HTTPClient client = getDefinition().getHttpClientProvider().newHTTPClient(transactionId);
			PlainMimeContentPart content = new PlainMimeContentPart(null, buffer,
				new MimeHeader("Content-Length", new Long(buffer.remainingData()).toString()),
				new MimeHeader("Content-Type", (getDefinition().getOperation().getDefinition().getSoapVersion() == 1.2 ? "application/soap+xml" : "text/xml") + "; charset=" + getDefinition().getCharset().displayName().toLowerCase()),
				new MimeHeader("Host", uri.getAuthority())
			);
			if (getDefinition().getOperation().getSoapAction() != null) {
				content.setHeader(new MimeHeader("SOAPAction", "\"" + getDefinition().getOperation().getSoapAction() + "\""));
			}
			HTTPResponse httpResponse = client.execute(
				new DefaultHTTPRequest("POST", definition.isUseFullPathTarget() ? uri.toString() : uri.getPath(), content), 
				principal, 
				endpoint.startsWith("https"), 
				true
			);
			if ((httpResponse.getCode() >= 200 && httpResponse.getCode() < 300) || (getDefinition().getAllowedHttpCodes() != null && getDefinition().getAllowedHttpCodes().contains(httpResponse.getCode()))) {
				ComplexContent response = parseOutput(((ContentPart) httpResponse.getContent()).getReadable(), getDefinition().getCharset());
				ComplexContent output = getDefinition().getOutput().newInstance();
				if (getDefinition().getOperation().getOperation().getOutput() != null && !getDefinition().getOperation().getOperation().getOutput().getParts().isEmpty()) {
					output.set("response", ((ComplexContent) response.get("Body")).get(getDefinition().getOperation().getOperation().getOutput().getParts().get(0).getElement().getName()));
				}
				if (getDefinition().getOperation().getOperation().getFaults() != null && !getDefinition().getOperation().getOperation().getFaults().isEmpty() && !getDefinition().getOperation().getOperation().getFaults().get(0).getParts().isEmpty()) {
					output.set("fault", ((ComplexContent) response.get("Body")).get(getDefinition().getOperation().getOperation().getFaults().get(0).getParts().get(0).getElement().getName()));
				}
				return output;
			}
			else {
				throw new ServiceException("SOAP-2", "HTTP Exception [" + httpResponse.getCode() + "] " + httpResponse.getMessage(), httpResponse.getCode(), httpResponse.getMessage());
			}
		}
		catch (IOException e) {
			throw new ServiceException(e);
		}
		catch (FormatException e) {
			throw new ServiceException(e);
		}
		catch (ParseException e) {
			throw new ServiceException(e);
		}
		catch (URISyntaxException e) {
			throw new ServiceException(e);
		}
	}

	private ComplexType buildRequestEnvelope(boolean isInput) {
		Structure envelope = new Structure();
		envelope.setName("Envelope");
		envelope.setProperty(new ValueImpl<Boolean>(new AttributeQualifiedDefaultProperty(), false));
		envelope.setProperty(new ValueImpl<Boolean>(new ElementQualifiedDefaultProperty(), true));
		if (getDefinition().getOperation().getDefinition().getSoapVersion() == 1.2) {
			envelope.setNamespace("http://www.w3.org/2003/05/soap-envelope");
		}
		// default is 1.1
		else {
			envelope.setNamespace("http://schemas.xmlsoap.org/soap/envelope/");
		}
		Structure header = new Structure();
		header.setName("Header");
		envelope.add(new ComplexElementImpl(header, envelope, new ValueImpl<Integer>(new MinOccursProperty(), 0)));
		
		Structure body = new Structure();
		body.setName("Body");
		body.setNamespace(envelope.getNamespace());
		if (isInput) {
			if (getDefinition().getOperation().getOperation().getInput() != null && !getDefinition().getOperation().getOperation().getInput().getParts().isEmpty()) {
				body.add(getDefinition().getOperation().getOperation().getInput().getParts().get(0).getElement());
			}
		}
		else {
			if (getDefinition().getOperation().getOperation().getOutput() != null && !getDefinition().getOperation().getOperation().getOutput().getParts().isEmpty()) {
				body.add(getDefinition().getOperation().getOperation().getOutput().getParts().get(0).getElement());
			}
			if (getDefinition().getOperation().getOperation().getFaults() != null && !getDefinition().getOperation().getOperation().getFaults().isEmpty() && !getDefinition().getOperation().getOperation().getFaults().get(0).getParts().isEmpty()) {
				body.add(getDefinition().getOperation().getOperation().getFaults().get(0).getParts().get(0).getElement());
			}
		}
		envelope.add(new ComplexElementImpl(body, envelope));
		return envelope;
	}
	
}

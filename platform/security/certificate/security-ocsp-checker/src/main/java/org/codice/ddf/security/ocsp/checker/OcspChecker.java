/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.security.ocsp.checker;

import static org.apache.commons.lang3.StringUtils.isBlank;

import com.google.common.annotations.VisibleForTesting;
import ddf.security.SecurityConstants;
import ddf.security.audit.SecurityLogger;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.AccessController;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedAction;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.Response;
import org.apache.cxf.jaxrs.client.WebClient;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.ocsp.BasicOCSPResponse;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.x509.extension.X509ExtensionUtil;
import org.codice.ddf.cxf.client.ClientBuilder;
import org.codice.ddf.cxf.client.ClientBuilderFactory;
import org.codice.ddf.cxf.client.SecureCxfClientFactory;
import org.codice.ddf.security.OcspService;
import org.codice.ddf.system.alerts.NoticePriority;
import org.codice.ddf.system.alerts.SystemNotice;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OcspChecker implements OcspService {
  private static final Logger LOGGER = LoggerFactory.getLogger(OcspChecker.class);
  private static final String NOT_VERIFIED_MSG = " The certificate status could not be verified.";
  private static final String CONTINUING_MSG = " Continuing OCSP check.";

  private final ClientBuilderFactory factory;
  private final EventAdmin eventAdmin;

  private boolean ocspEnabled; // metatype value
  private List<URI> ocspServerUrls = new ArrayList<>(); // metatype value

  private SecurityLogger securityLogger;

  public OcspChecker(ClientBuilderFactory factory, EventAdmin eventAdmin) {
    this.factory = factory;
    this.eventAdmin = eventAdmin;
  }

  /**
   * Checks whether the given {@param certs} are revoked or not against the configured OCSP server
   * urls + the optionally given OCSP server url in the given {@param certs}.
   *
   * @param certs - an array of certificates to verify.
   * @return true if the certificates are good or if they could not be properly checked against the
   *     OCSP server. Returns false if any of them are revoked.
   */
  @Override
  public boolean passesOcspCheck(X509Certificate[] certs) {
    if (!ocspEnabled) {
      LOGGER.debug("OCSP check is not enabled. Skipping.");
      return true;
    }

    LOGGER.debug("OCSP check for {} certificate(s)", certs == null ? "0" : certs.length);
    for (X509Certificate cert : certs) {
      try {
        Certificate certificate = convertToBouncyCastleCert(cert);
        OCSPReq ocspRequest = generateOcspRequest(certificate);
        Map<URI, CertificateStatus> ocspStatuses = sendOcspRequests(cert, ocspRequest);
        URI revokedStatusUrl = getFirstRevokedStatusUrl(ocspStatuses);
        if (revokedStatusUrl != null) {
          securityLogger.audit(
              "Certificate {} has been revoked by the OCSP server at URL {}.",
              cert,
              revokedStatusUrl);
          LOGGER.warn(
              "Certificate {} has been revoked by the OCSP server at URL {}.",
              cert,
              revokedStatusUrl);
          return false;
        }
        LOGGER.debug("No certificates revoked by the OCSP server");
      } catch (OcspCheckerException e) {
        postErrorEvent(e.getMessage());
      }
    }
    // If an error occurred, the certificates will not be validated and will be permitted.
    // An alert will be posted to the admin console.
    return true;
  }

  /**
   * Converts a {@link java.security.cert.X509Certificate} to a {@link Certificate}.
   *
   * @param cert - the X509Certificate to convert.
   * @return a {@link Certificate}.
   * @throws OcspCheckerException after posting an alert to the admin console, if any error occurs.
   */
  @VisibleForTesting
  Certificate convertToBouncyCastleCert(X509Certificate cert) throws OcspCheckerException {
    try {
      byte[] data = cert.getEncoded();
      return Certificate.getInstance(data);
    } catch (CertificateEncodingException e) {
      throw new OcspCheckerException(
          "Unable to convert X509 certificate to a Bouncy Castle certificate." + NOT_VERIFIED_MSG,
          e);
    }
  }

  /**
   * Creates an {@link OCSPReq} to send to the OCSP server for the given certificate.
   *
   * @param cert - the certificate to verify
   * @return the created OCSP request
   * @throws OcspCheckerException after posting an alert to the admin console, if any error occurs
   */
  @VisibleForTesting
  OCSPReq generateOcspRequest(Certificate cert) throws OcspCheckerException {
    try {
      X509CertificateHolder issuerCert = resolveIssuerCertificate(cert);

      JcaDigestCalculatorProviderBuilder digestCalculatorProviderBuilder =
          new JcaDigestCalculatorProviderBuilder();
      DigestCalculatorProvider digestCalculatorProvider = digestCalculatorProviderBuilder.build();
      DigestCalculator digestCalculator = digestCalculatorProvider.get(CertificateID.HASH_SHA1);

      CertificateID certId =
          new CertificateID(digestCalculator, issuerCert, cert.getSerialNumber().getValue());

      OCSPReqBuilder ocspReqGenerator = new OCSPReqBuilder();
      ocspReqGenerator.addRequest(certId);
      return ocspReqGenerator.build();

    } catch (OCSPException | OperatorCreationException e) {
      throw new OcspCheckerException("Unable to create an OCSP request." + NOT_VERIFIED_MSG, e);
    }
  }

  /**
   * Returns an {@link X509CertificateHolder} containing the issuer of the passed in {@param cert}.
   * Search is performed in the system truststore.
   *
   * @param cert - the {@link Certificate} to get the issuer from.
   * @return {@link X509CertificateHolder} containing the issuer of the passed in {@param cert}.
   * @throws OcspCheckerException if the issuer cannot be resolved.
   */
  private X509CertificateHolder resolveIssuerCertificate(Certificate cert)
      throws OcspCheckerException {
    X500Name issuerName = cert.getIssuer();

    String trustStorePath =
        AccessController.doPrivileged(
            (PrivilegedAction<String>) SecurityConstants::getTruststorePath);
    String trustStorePass =
        AccessController.doPrivileged(
            (PrivilegedAction<String>) SecurityConstants::getTruststorePassword);

    if (isBlank(trustStorePath) || isBlank(trustStorePass)) {
      throw new OcspCheckerException(
          "Problem retrieving truststore properties." + NOT_VERIFIED_MSG);
    }

    KeyStore truststore;

    try (InputStream truststoreInputStream = new FileInputStream(trustStorePath)) {
      truststore = SecurityConstants.newTruststore();
      truststore.load(truststoreInputStream, trustStorePass.toCharArray());
      securityLogger.audit(
          "Truststore on path {} was read by {}.", trustStorePath, this.getClass().getSimpleName());
    } catch (CertificateException | IOException | KeyStoreException | NoSuchAlgorithmException e) {
      throw new OcspCheckerException(
          String.format("Problem loading truststore on path %s", trustStorePath), e);
    }

    try {
      return getCertFromTruststoreWithX500Name(issuerName, truststore);
    } catch (OcspCheckerException e) {
      throw new OcspCheckerException(
          "Problem finding the certificate issuer in truststore." + NOT_VERIFIED_MSG, e);
    }
  }

  /**
   * Returns an {@link X509CertificateHolder} containing the issuer of the given {@param name}.
   * Search is performed in the given {@param truststore}.
   *
   * @param name - the {@link X500Name} of the issuer.
   * @param truststore - the {@link KeyStore} to check.
   * @return {@link X509CertificateHolder} of the certificate with the given {@param name}.
   * @throws OcspCheckerException if the {@param name} cannot be found in the {@param truststore}.
   */
  private X509CertificateHolder getCertFromTruststoreWithX500Name(
      X500Name name, KeyStore truststore) throws OcspCheckerException {
    Enumeration<String> aliases;

    try {
      aliases = truststore.aliases();
    } catch (KeyStoreException e) {
      throw new OcspCheckerException(
          "Problem getting aliases from truststore." + NOT_VERIFIED_MSG, e);
    }

    while (aliases.hasMoreElements()) {
      String currentAlias = aliases.nextElement();

      try {
        java.security.cert.Certificate currentCert = truststore.getCertificate(currentAlias);
        X509CertificateHolder currentCertHolder =
            new X509CertificateHolder(currentCert.getEncoded());
        X500Name currentName = currentCertHolder.getSubject();
        if (name.equals(currentName)) {
          return currentCertHolder;
        }
      } catch (CertificateEncodingException | IOException | KeyStoreException e) {
        LOGGER.debug("Problem loading truststore certificate." + CONTINUING_MSG, e);
      }
    }

    throw new OcspCheckerException(
        String.format("Could not find cert matching X500Name of %s.", name) + NOT_VERIFIED_MSG);
  }

  /**
   * Sends the {@param ocspReq} request to all configured {@code cspServerUrls} & the OCSP server
   * urls optionally given in the given {@param cert}.
   *
   * @param cert - the {@link X509Certificate} to check.
   * @param ocspRequest - the {@link OCSPReq} to send.
   * @return a {@link List} of {@link OCSPResp}s for every configured {@code ocspServerUrls} & the
   *     OCSP server * urls optionally given in the given {@param cert}. Problematic responses are
   *     represented as null values.
   */
  @VisibleForTesting
  Map<URI, CertificateStatus> sendOcspRequests(X509Certificate cert, OCSPReq ocspRequest) {
    Set<URI> urlsToCheck = new HashSet<>();
    if (ocspServerUrls != null) {
      urlsToCheck.addAll(ocspServerUrls);
    }

    // try and pull an OCSP server url off of the cert
    urlsToCheck.addAll(getOcspUrlsFromCert(cert));

    if (LOGGER.isTraceEnabled()) {
      logRequest(ocspRequest);
    }

    Map<URI, CertificateStatus> ocspStatuses = new HashMap<>();

    for (URI ocspServerUrl : urlsToCheck) {
      try {
        ClientBuilder<WebClient> clientBuilder = factory.getClientBuilder();
        SecureCxfClientFactory<WebClient> cxfClientFactory =
            clientBuilder.endpoint(ocspServerUrl).interfaceClass(WebClient.class).build();
        WebClient client =
            cxfClientFactory
                .getWebClient()
                .accept("application/ocsp-response")
                .type("application/ocsp-request");

        LOGGER.debug("Sending OCSP request to URL: {}", ocspServerUrl);
        Response response = client.post(ocspRequest.getEncoded());
        OCSPResp ocspResponse = createOcspResponse(response);
        if (LOGGER.isTraceEnabled()) {
          logResponse(ocspResponse);
        }
        ocspStatuses.put(ocspServerUrl, getStatusFromOcspResponse(ocspResponse, cert));
        continue;
      } catch (IOException | OcspCheckerException | ProcessingException e) {
        LOGGER.debug(
            "Problem with the response from the OCSP Server at URL {}." + CONTINUING_MSG,
            ocspServerUrl,
            e);
      }
      ocspStatuses.put(
          ocspServerUrl,
          new UnknownStatus()); // if ocspServerUrl is null or if there was an exception
    }

    return ocspStatuses;
  }

  /**
   * Attempts to grab additional OCSP server urls off of the given {@param cert}.
   *
   * @param - the {@link X509Certificate} to check.
   * @return {@link List} of additional OCSP server urls found on the given {@param cert}.
   */
  private List<URI> getOcspUrlsFromCert(X509Certificate cert) {
    List<URI> ocspUrls = new ArrayList<>();

    try {
      byte[] authorityInfoAccess = cert.getExtensionValue(Extension.authorityInfoAccess.getId());

      if (authorityInfoAccess == null) {
        return ocspUrls;
      }

      AuthorityInformationAccess authorityInformationAccess =
          AuthorityInformationAccess.getInstance(
              X509ExtensionUtil.fromExtensionValue(authorityInfoAccess));

      if (authorityInformationAccess == null) {
        return ocspUrls;
      }

      for (AccessDescription description : authorityInformationAccess.getAccessDescriptions()) {
        GeneralName accessLocation = description.getAccessLocation();
        if (accessLocation.getTagNo() == GeneralName.uniformResourceIdentifier)
          try {
            ocspUrls.add(new URI(((DERIA5String) accessLocation.getName()).getString()));
          } catch (URISyntaxException e) {
            LOGGER.debug("Location is not a URI.", e);
          }
      }
    } catch (IOException e) {
      LOGGER.debug(
          "Problem retrieving the OCSP server url(s) from the certificate." + CONTINUING_MSG, e);
    }

    return ocspUrls;
  }

  /**
   * Creates a {@link OCSPResp} from the given {@param response}.
   *
   * @param response - the {@link Response} to convert.
   * @return an {@link OCSPResp} of the given {@param response}.
   * @throws OcspCheckerException if any error occurs.
   */
  private OCSPResp createOcspResponse(Response response) throws OcspCheckerException {
    Object entity = response.getEntity();

    if (!(entity instanceof InputStream)) {
      throw new OcspCheckerException("Response did not contain an entity of type InputStream.");
    }

    try (InputStream inputStream = (InputStream) entity) {
      return new OCSPResp(inputStream);
    } catch (IOException e) {
      throw new OcspCheckerException("Problem converting the HTTP Response to an OCSPResp.", e);
    }
  }

  /**
   * Gets the {@link CertificateStatus} from the given {@param ocspResponse}.
   *
   * @param ocspResponse - the {@link OCSPResp} to get the {@link CertificateStatus} from.
   * @return the {@link CertificateStatus} from the given {@param ocspResponse}. Returns an {@link
   *     UnknownStatus} if the status could not be found.
   */
  private CertificateStatus getStatusFromOcspResponse(
      OCSPResp ocspResponse, X509Certificate certificate) {
    try {
      BasicOCSPResp basicResponse = (BasicOCSPResp) ocspResponse.getResponseObject();

      if (basicResponse == null) {
        return new UnknownStatus();
      }

      SingleResp[] singleResps = basicResponse.getResponses();
      if (singleResps == null) {
        return new UnknownStatus();
      }
      SingleResp response =
          Arrays.stream(singleResps)
              .filter(singleResp -> singleResp.getCertID() != null)
              .filter(
                  singleResp ->
                      singleResp
                          .getCertID()
                          .getSerialNumber()
                          .equals(certificate.getSerialNumber()))
              .findFirst()
              .orElse(null);
      if (response == null) {
        LOGGER.debug("Certificate status from OCSP response is unknown.");
        return new UnknownStatus();
      }
      if (response.getCertStatus() == null) {
        LOGGER.debug("Certificate status from OCSP response is good.");
        return CertificateStatus.GOOD;
      }
      return response.getCertStatus();
    } catch (OCSPException e) {
      return new UnknownStatus();
    }
  }

  /**
   * Check if any {@link CertificateStatus} in the given {@param ocspStatuses} are revoked.
   *
   * @param ocspStatuses - a {@link Map} of OCSP URLs and their respective {@link
   *     CertificateStatus}.
   * @return the URL of the first revoked status, or null if no revoked status was found.
   */
  private @Nullable URI getFirstRevokedStatusUrl(Map<URI, CertificateStatus> ocspStatuses) {
    return ocspStatuses
        .entrySet()
        .stream()
        .filter(entry -> entry.getValue() instanceof RevokedStatus)
        .map(Entry::getKey)
        .findFirst()
        .orElse(null);
  }

  /**
   * Posts and error message to the Admin Console.
   *
   * @param errorMessage - The reason for the error.
   */
  private void postErrorEvent(String errorMessage) {
    String title = "Problem checking the revocation status of the Certificate through OCSP.";
    Set<String> details = new HashSet<>();
    details.add(
        "An error occurred while checking the revocation status of a Certificate against an Online Certificate Status Protocol (OCSP) server. "
            + "Please resolve the error to resume validating certificates against the OCSP server.");
    details.add(errorMessage);
    eventAdmin.postEvent(
        new Event(
            SystemNotice.SYSTEM_NOTICE_BASE_TOPIC + "crl",
            new SystemNotice(this.getClass().getName(), NoticePriority.CRITICAL, title, details)
                .getProperties()));
    securityLogger.audit(title);
    securityLogger.audit(errorMessage);
    LOGGER.debug(errorMessage);
  }

  private void logRequest(OCSPReq ocspRequest) {
    StringBuilder logBuilder = new StringBuilder();
    logBuilder.append("OCSP Request:\n");
    logBuilder.append("  TBSRequest:\n");
    logBuilder.append(
        "    version: " + getValueOrDefault(ocspRequest.getVersionNumber(), "") + "\n");
    logBuilder.append(
        "    requestorName: "
            + getValueOrDefault(ocspRequest.getRequestorName(), "").toString()
            + "\n");
    logBuilder.append("    requestList:\n");
    Req[] requests = ocspRequest.getRequestList();
    if (requests != null) {
      for (int i = 0; i < requests.length; i++) {
        logBuilder.append("      Certificate " + i + "\n");
        CertificateID cert = requests[i].getCertID();
        if (cert != null) {
          logBuilder.append(
              "        hashAlgorithm: "
                  + getValueOrDefault(cert.getHashAlgOID(), "").toString()
                  + "\n");
          logBuilder.append(
              "        issuerNameHash: "
                  + getValueOrDefault(Arrays.toString(cert.getIssuerNameHash()), "")
                  + "\n");
          logBuilder.append(
              "        issuerKeyHash: "
                  + getValueOrDefault(Arrays.toString(cert.getIssuerKeyHash()), "")
                  + "\n");
          logBuilder.append(
              "        cert serial number: "
                  + getValueOrDefault(cert.getSerialNumber(), "").toString()
                  + "\n");
        }
      }
    }
    LOGGER.trace(logBuilder.toString());
  }

  private void logResponse(OCSPResp response) {
    BasicOCSPResp basicOCSPResp;
    BasicOCSPResponse basicOCSPResponse;
    try {
      basicOCSPResp = (BasicOCSPResp) response.getResponseObject();
      basicOCSPResponse =
          BasicOCSPResponse.getInstance(
              ((BasicOCSPResp) response.getResponseObject()).getEncoded());
      StringBuilder logBuilder = new StringBuilder();
      logBuilder.append("OCSP Response: \n");
      logBuilder.append("  responseStatus: " + getValueOrDefault(response.getStatus(), "") + "\n");
      logBuilder.append("  responseBytes:\n");
      logBuilder.append(
          "  responseType: "
              + getValueOrDefault(basicOCSPResponse, "").getClass().getSimpleName()
              + "\n");
      logBuilder.append("    response:\n");
      logBuilder.append("      tbsResponseData:\n");
      if (basicOCSPResponse.getTbsResponseData() != null) {
        logBuilder.append(
            "        version: "
                + getValueOrDefault(basicOCSPResponse.getTbsResponseData().getVersion(), "")
                    .toString()
                + "\n");
        logBuilder.append("        responderId:\n");
        if (basicOCSPResponse.getTbsResponseData().getResponderID() != null) {
          logBuilder.append(
              "          byName: "
                  + getValueOrDefault(
                          basicOCSPResponse.getTbsResponseData().getResponderID().getName(), "")
                      .toString()
                  + "\n");
          logBuilder.append(
              "          byKey: "
                  + getValueOrDefault(
                      Arrays.toString(
                          basicOCSPResponse.getTbsResponseData().getResponderID().getKeyHash()),
                      "")
                  + "\n");
        } else {
          logBuilder.append("          byName:\n");
        }
        if (basicOCSPResponse.getTbsResponseData().getProducedAt() != null) {
          logBuilder.append(
              "        producedAt: "
                  + getValueOrDefault(
                      basicOCSPResponse.getTbsResponseData().getProducedAt().getTimeString(), "")
                  + "\n");
        } else {
          logBuilder.append("        producedAt:\n");
        }
      }
      logBuilder.append("        responses:\n");
      if (basicOCSPResp.getResponses() != null) {
        SingleResp[] singleResps = basicOCSPResp.getResponses();
        for (int i = 0; i < singleResps.length; i++) {
          CertificateID certificateID = singleResps[i].getCertID();
          if (certificateID != null) {
            logBuilder.append("        certID #: " + i + "\n");
            logBuilder.append(
                "          hashAlgorithm: "
                    + getValueOrDefault(certificateID.getHashAlgOID(), "").toString()
                    + "\n");
            logBuilder.append(
                "          issuerNameHash: "
                    + getValueOrDefault(Arrays.toString(certificateID.getIssuerNameHash()), "")
                    + "\n");
            logBuilder.append(
                "          issuerKeyHash: "
                    + getValueOrDefault(Arrays.toString(certificateID.getIssuerKeyHash()), "")
                    + "\n");
            logBuilder.append(
                "          cert serial number: "
                    + getValueOrDefault(certificateID.getSerialNumber(), "")
                    + "\n");
            logBuilder.append(
                "        certStatus: "
                    + getValueOrDefault(singleResps[i].getCertStatus(), "good")
                        .getClass()
                        .getSimpleName()
                    + "\n");
            logBuilder.append(
                "        thisUpdate: "
                    + getValueOrDefault(singleResps[i].getThisUpdate(), "").toString()
                    + "\n");
            logBuilder.append(
                "        nextUpdate: "
                    + getValueOrDefault(singleResps[i].getNextUpdate(), "").toString()
                    + "\n");
          }
        }
      }
      if (basicOCSPResp.getSignatureAlgorithmID() != null) {
        logBuilder.append(
            "      signatureAlgorithm: "
                + getValueOrDefault(basicOCSPResp.getSignatureAlgorithmID().getAlgorithm(), "")
                    .toString()
                + "\n");
      }
      logBuilder.append(
          "      signature: "
              + getValueOrDefault(Arrays.toString(basicOCSPResp.getSignature()), "")
              + "\n");
      logBuilder.append("      certs:\n");
      if (basicOCSPResp.getCerts() != null) {
        X509CertificateHolder[] x509CertificateHolders = basicOCSPResp.getCerts();
        for (int i = 0; i < x509CertificateHolders.length; i++) {
          logBuilder.append("        certificate: " + i + "\n");
          logBuilder.append(
              "          issuer: "
                  + getValueOrDefault(x509CertificateHolders[i].getIssuer(), "").toString()
                  + "\n");
          logBuilder.append(
              "          subject: "
                  + getValueOrDefault(x509CertificateHolders[i].getSubject(), "").toString()
                  + "\n");
          if (basicOCSPResp.getSignatureAlgorithmID() != null) {
            logBuilder.append(
                "          signatureAlgorithm: "
                    + getValueOrDefault(
                            x509CertificateHolders[i].getSignatureAlgorithm().getAlgorithm(), "")
                        .toString()
                    + "\n");
          }
          logBuilder.append(
              "          start date: "
                  + getValueOrDefault(
                          x509CertificateHolders[i].toASN1Structure().getStartDate(), "")
                      .toString()
                  + "\n");
          logBuilder.append(
              "          end date: "
                  + getValueOrDefault(x509CertificateHolders[i].toASN1Structure().getEndDate(), "")
                      .toString()
                  + "\n");
          logBuilder.append(
              "          cert serial number: "
                  + getValueOrDefault(x509CertificateHolders[i].getSerialNumber(), "")
                  + "\n");
        }
      }
      LOGGER.trace(logBuilder.toString());
    } catch (IOException | OCSPException e) {
      LOGGER.trace("Could not log response, issue converting response to a BasicOcspResponse.", e);
    }
  }

  public void setOcspEnabled(boolean ocspEnabled) {
    this.ocspEnabled = ocspEnabled;
  }

  public void setOcspServerUrls(List<String> ocspServerUrls) {
    this.ocspServerUrls =
        ocspServerUrls
            .stream()
            .map(
                str -> {
                  try {
                    return new URI(str);
                  } catch (URISyntaxException e) {
                    LOGGER.warn("OCSP URL is not URI.", e);
                  }
                  return null;
                })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
  }

  /**
   * Custom exception usually thrown after an unexpected error occurred while validating a
   * certificate. An alert should be posted to the admin console first.
   */
  class OcspCheckerException extends Exception {
    public OcspCheckerException() {
      super();
    }

    public OcspCheckerException(Exception cause) {
      super(cause);
    }

    public OcspCheckerException(String msg) {
      super(msg);
    }

    public OcspCheckerException(String msg, Exception cause) {
      super(msg, cause);
    }
  }

  private static <T> T getValueOrDefault(T value, T defaultValue) {
    return value == null ? defaultValue : value;
  }

  public void setSecurityLogger(SecurityLogger securityLogger) {
    this.securityLogger = securityLogger;
  }
}

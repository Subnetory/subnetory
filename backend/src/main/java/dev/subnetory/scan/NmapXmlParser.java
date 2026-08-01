package dev.subnetory.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parseur de sortie XML Nmap.
 *
 * <p>Nmap est appelé avec {@code -oX -} (sortie XML sur stdout).
 * Ce parseur extrait les hôtes UP avec leur IP, MAC (si présente) et hostname PTR.</p>
 *
 * <p>Utilise le DOM standard Java — aucune dépendance externe.</p>
 *
 * <p>Structure XML Nmap analysée :</p>
 * <pre>{@code
 * <nmaprun>
 *   <host>
 *     <status state="up"/>
 *     <address addr="192.168.1.10" addrtype="ipv4"/>
 *     <address addr="aa:bb:cc:dd:ee:ff" addrtype="mac" vendor="Dell"/>  <!-- si même L2 -->
 *     <hostnames>
 *       <hostname name="srv-web-01.local" type="PTR"/>
 *     </hostnames>
 *   </host>
 * </nmaprun>
 * }</pre>
 */
public class NmapXmlParser {

    private static final Logger log = LoggerFactory.getLogger(NmapXmlParser.class);

    /**
     * Résultat pour un hôte découvert par Nmap.
     *
     * @param ip       adresse IPv4 (toujours présente)
     * @param mac      adresse MAC au format "aa:bb:cc:dd:ee:ff" (null si subnet distant)
     * @param hostname hostname PTR (null si pas de reverse DNS)
     */
    public record NmapHost(String ip, String mac, String hostname) {}

    private NmapXmlParser() {}

    /**
     * Parse la sortie XML de Nmap depuis un InputStream (stdout du process).
     *
     * @param xmlInput stream contenant le XML Nmap
     * @return liste des hôtes UP avec leurs informations
     */
    public static List<NmapHost> parse(InputStream xmlInput) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Protection XXE : autoriser le DOCTYPE déclaratif Nmap (<!DOCTYPE nmaprun>)
        // mais interdire les entités externes et le chargement DTD externe.
        // NE PAS utiliser disallow-doctype-decl=true : cela bloque le DOCTYPE Nmap légitime.
        configureXxeProtection(factory);

        var builder = factory.newDocumentBuilder();
        // EntityResolver neutre : neutralise les entités résiduelles sans accès réseau
        builder.setEntityResolver((pub, sys) -> new InputSource(new java.io.StringReader("")));
        Document doc = builder.parse(xmlInput);
        return extractHosts(doc);
    }

    /**
     * Parse depuis une String — utilisé dans les tests unitaires.
     */
    public static List<NmapHost> parseString(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        configureXxeProtection(factory);

        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((pub, sys) -> new InputSource(new StringReader("")));
        Document doc = builder.parse(new InputSource(new StringReader(xml)));
        return extractHosts(doc);
    }


    /**
     * Configure la protection XXE sur une DocumentBuilderFactory.
     *
     * <p>Le DOCTYPE déclaratif Nmap ({@code <!DOCTYPE nmaprun>}) est autorisé.
     * Les entités externes, le chargement DTD externe et les schemas externes
     * sont interdits pour prévenir les attaques XXE.</p>
     */
    private static void configureXxeProtection(DocumentBuilderFactory factory) {
        try {
            // Activer le traitement sécurisé
            factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
            // Interdire les entités externes (XXE)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            // Interdire le chargement de DTD externe
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            // Interdire les schémas externes via JAXP
            factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
            factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
            factory.setExpandEntityReferences(false);
            factory.setValidating(false);
            // EntityResolver neutre — positionné sur le builder dans parse() et parseString()
        } catch (Exception e) {
            // Les features peuvent varier selon l'implémentation XML
            // Le parsing reste fonctionnel même si une feature n'est pas supportée
            log.warn("Some XXE protection features unavailable: {}", e.getMessage());
        }
    }

    private static List<NmapHost> extractHosts(Document doc) {
        List<NmapHost> hosts = new ArrayList<>();
        NodeList hostNodes = doc.getElementsByTagName("host");

        for (int i = 0; i < hostNodes.getLength(); i++) {
            Element host = (Element) hostNodes.item(i);

            // Ne conserver que les hôtes UP
            NodeList statusNodes = host.getElementsByTagName("status");
            if (statusNodes.getLength() == 0) continue;
            Element status = (Element) statusNodes.item(0);
            if (!"up".equals(status.getAttribute("state"))) continue;

            String ip = null;
            String mac = null;
            String hostname = null;

            // Extraire les adresses
            NodeList addressNodes = host.getElementsByTagName("address");
            for (int j = 0; j < addressNodes.getLength(); j++) {
                Element addr = (Element) addressNodes.item(j);
                String type = addr.getAttribute("addrtype");
                String value = addr.getAttribute("addr");
                if ("ipv4".equals(type)) {
                    ip = value;
                } else if ("mac".equals(type) && value != null && !value.isBlank()) {
                    // Normaliser en minuscules — cohérent avec le stockage PostgreSQL macaddr
                    mac = value.toLowerCase().trim();
                }
            }

            // Extraire le hostname PTR (reverse DNS)
            NodeList hostnameNodes = host.getElementsByTagName("hostname");
            for (int j = 0; j < hostnameNodes.getLength(); j++) {
                Element h = (Element) hostnameNodes.item(j);
                if ("PTR".equals(h.getAttribute("type")) || "user".equals(h.getAttribute("type"))) {
                    String name = h.getAttribute("name");
                    if (name != null && !name.isBlank()) {
                        // Supprimer le point final des FQDN (ex: "srv.local." → "srv.local")
                        hostname = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
                        break;
                    }
                }
            }

            if (ip != null) {
                hosts.add(new NmapHost(ip, mac, hostname));
                log.debug("Nmap host parsed: ip={} mac={} hostname={}", ip, mac, hostname);
            }
        }

        log.info("Nmap XML parsed: {} host(s) UP", hosts.size());
        return hosts;
    }
}

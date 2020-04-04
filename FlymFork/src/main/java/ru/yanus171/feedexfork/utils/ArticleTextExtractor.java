package ru.yanus171.feedexfork.utils;

import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import ru.yanus171.feedexfork.R;
import ru.yanus171.feedexfork.service.FetcherService;

/**
 * This class is thread safe.
 *
 * @author Alex P (ifesdjeen from jreadability)
 * @author Peter Karich
 */
public class ArticleTextExtractor {

    // Interesting nodes
    private static final Pattern NODES = Pattern.compile("p|div|td|h1|h2|article|section");

    // Unlikely candidates
    private static final Pattern UNLIKELY = Pattern.compile("com(bx|ment|munity)|dis(qus|cuss)|e(xtra|[-]?mail)|foot|"
            + "header|menu|re(mark|ply)|rss|sh(are|outbox)|sponsor"
            + "a(d|ll|gegate|rchive|ttachment)|(pag(er|ination))|popup|print|"
            + "login|si(debar|gn|ngle)");

    // Most likely positive candidates
    private static final Pattern POSITIVE = Pattern.compile("(^(body|content|h?entry|main|page|post|text|blog|story|haupt))"
            + "|arti(cle|kel)|instapaper_body");

    // Most likely negative candidates
    //private static final Pattern NEGATIVE = Pattern.compile("nav($|igation)|user|com(ment|bx)|(^com-)|contact|"
    private static final Pattern NEGATIVE = Pattern.compile("nav($|igation)|user|combx|(^com-)|contact|"
            + "foot|masthead|(me(dia|ta))|outbrain|promo|related|scroll|(sho(utbox|pping))|"
            + "sidebar|sponsor|tags|tool|widget|player|disclaimer|toc|infobox|vcard");

    private static final Pattern NEGATIVE_STYLE =
            Pattern.compile("hidden|display: ?none|font-size: ?small");
    static final String TAG_BUTTON_CLASS = "tag_button";
    static final String TAG_BUTTON_CLASS_HIDDEN = "tag_button_hidden";
    static final String TAG_BUTTON_FULL_TEXT_ROOT_CLASS = "tag_button_full_text";
    static final String CLASS_ATTRIBUTE = "class";
    public static final String HANDY_NEWS_READER_ROOT_CLASS = "Handy_News_Reader_root";
    public static final String P_HR = "</p><hr>";

    public enum MobilizeType {Yes, No, Tags}
    public static final String BEST_ELEMENT_ATTR = "BEST_ELEMENT";

    public static String mLastLoadedAllDoc = "";

    /**
     * @param input            extracts article text from given html string. wasn't tested
     *                         with improper HTML, although jSoup should be able to handle minor stuff.
     * @param contentIndicator a text which should be included into the extracted content, or null
     * @return extracted article, all HTML tags stripped
     */
    public static String extractContent(InputStream input,
                                        final String url,
                                        String contentIndicator,
                                        MobilizeType mobilize,
                                        boolean isFindBEstElement,
                                        boolean isWithTables ) throws Exception {
        return extractContent(Jsoup.parse(input, null, ""),
                              url,
                              contentIndicator,
                              mobilize,
                              isFindBEstElement,
                              isWithTables);
    }

    public static String extractContent(Document doc,
                                        final String url,
                                        String contentIndicator,
                                        MobilizeType mobilize,
                                        boolean isFindBestElement,
                                        boolean isWithTables) {
        if (doc == null)
            throw new NullPointerException("missing document");

        FetcherService.Status().AddBytes(doc.html().length());

        // now remove the clutter
        prepareDocument(doc, mobilize);

        Element rootElement = doc;
        if ( mobilize == MobilizeType.Yes) {
            rootElement = FindBestElement(doc, url, contentIndicator, isFindBestElement);
            RemoveHiddenElements(rootElement);
        }

        if ( rootElement == null )
            rootElement = doc;

        Collection<Element> metas = getMetas(doc);
        String ogImage = null;
        for (Element entry : metas) {
            if (entry.hasAttr("property") && "og:image".equals(entry.attr("property"))) {
                ogImage = entry.attr("content");
                break;
            }
        }


        Elements title = rootElement.getElementsByClass("title");
        for (Element entry : title)
            if (entry.tagName().toLowerCase().equals("h1")) {
                title.first().remove();
                break;
            }

        String ret = rootElement.toString();
        if (ogImage != null && !ret.contains(ogImage)) {
            ret = "<img src=\"" + ogImage + "\"><br>\n" + ret;
        }


        if ( mobilize == MobilizeType.Yes && PrefUtils.getBoolean(PrefUtils.LOAD_COMMENTS, false) ) {
            Element comments = doc.getElementById("comments");
            if (comments != null) {
                Elements li = comments.getElementsByTag("li");
                for (Element entry : li) {
                    entry.tagName("p");
                }
                Elements ul = comments.getElementsByTag("ul");
                for (Element entry : ul) {
                    entry.tagName("p");
                }
                ret += comments;
            }
        }

        if ( !isWithTables ) {
            ret = RemoveTables(ret);
        }


        ret = HtmlUtils.improveHtmlContent(ret, NetworkUtils.getBaseUrl(url), mobilize);

        if ( mobilize == MobilizeType.Tags ) {
            mLastLoadedAllDoc = ret;
            Document tempDoc = Jsoup.parse(ret, NetworkUtils.getUrlDomain(url));
            rootElement = FindBestElement(tempDoc, url, contentIndicator, isFindBestElement);
            AddTagButtons(tempDoc, url, rootElement);
            ret = tempDoc.toString();
        }


        return ret;
    }

    public static void RemoveHiddenElements(Element rootElement) {
        final ArrayList<String> removeClassList = PrefUtils.GetRemoveClassList();
        for (String classItem : removeClassList) {
            Elements list = rootElement.getElementsByClass(classItem);
            for (Element item : list) {
                item.remove();
            }
        }
    }

    @NotNull
    public static String RemoveTables(String ret) {
        ret = ret.replaceAll("<table(.)*?>", "<p>");
        ret = ret.replaceAll("</table>", "</p>");

        ret = ret.replaceAll("<tr(.)*?>", "<p>");
        ret = ret.replaceAll("</tr>", P_HR);

        ret = ret.replaceAll("<td(.)*?>", "<p>");
        ret = ret.replaceAll("</td>", "</p>");

        ret = ret.replaceAll("<th(.)*?>", "<p>");
        ret = ret.replaceAll("</th>", P_HR);
        return ret;
    }

    @NotNull
    public static String RemoveHeaders(String ret) {
        ret = ret.replaceAll("<h1(.)*?>", "");
        ret = ret.replaceAll("</h1>", "");

        ret = ret.replaceAll("<h2(.)*?>", "");
        ret = ret.replaceAll("</h2>", "");

        ret = ret.replaceAll("<h3(.)*?>", "");
        ret = ret.replaceAll("</h3>", "");

        return ret;
    }

    public static Element FindBestElement(Document doc, String url, String contentIndicator, boolean isFindBestElement) {

        // init elements
        Collection<Element> nodes = getNodes(doc);
        int maxWeight = 0;

        Element bestMatchElement = getBestElementFromFile(doc, url);
        if (bestMatchElement == null && isFindBestElement) {
            for (Element entry : nodes) {
                int currentWeight = getWeight(entry, contentIndicator);
                if (currentWeight > maxWeight) {
                    maxWeight = currentWeight;
                    bestMatchElement = entry;

                    if (maxWeight > 300) {
                        break;
                    }
                }
            }
        }
        if ( bestMatchElement == null )
            bestMatchElement = doc;


        return bestMatchElement;
    }

    public static void AddTagButtons(Document doc, String url, Element bestMatchElement) {
        doc.addClass( HANDY_NEWS_READER_ROOT_CLASS );

        final ArrayList<String> removeClassList = PrefUtils.GetRemoveClassList();

        final String baseUrl = NetworkUtils.getUrlDomain(url);
        for (Element el : doc.getElementsByAttribute(CLASS_ATTRIBUTE))
            if (el.hasText()) {
                //final String classNameList =
                //       el.attr(CLASS_ATTRIBUTE).trim().replaceAll("\\r|\\n", " ").replaceAll(" +", " ");
                final Set<String> classNameList = el.classNames();
                if ( classNameList.contains( TAG_BUTTON_CLASS) )
                    continue;
                for ( String className: classNameList ) {
                    if ( className.trim().isEmpty() )
                        continue;
                    boolean isHidden = removeClassList.contains(className);
                    AddTagButton(el, className, baseUrl, isHidden, el == bestMatchElement || el.hasAttr( BEST_ELEMENT_ATTR ) );
                    if ( isHidden && el.parent() != null ) {
                        Element elementS = doc.createElement("s");
                        elementS.addClass( TAG_BUTTON_CLASS_HIDDEN );
                        el.replaceWith(elementS);
                        elementS.insertChildren(0, el);
                    }
                }
            }
    }

    private static void AddTagButton(Element el, String className, String baseUrl, boolean isHidden, boolean isFullTextRoot) {
        final String paramValue = isHidden ? "show" : "hide";
        final String methodText = "openTagMenu('" + className + "', '" + baseUrl + "', '" + paramValue + "')";
        //final String fullTextRoot = isFullTextRoot ? " !!! " + MainApplication.getContext().getString( R.string.fullTextRoot ).toUpperCase() + " !!! " : "";
        final String tagClass = isFullTextRoot ? TAG_BUTTON_FULL_TEXT_ROOT_CLASS : ( isHidden ? TAG_BUTTON_CLASS_HIDDEN : TAG_BUTTON_CLASS );
        el.append(HtmlUtils.getButtonHtml(methodText,  " " + className + " ]", tagClass));
        el.prepend(HtmlUtils.getButtonHtml(methodText,  "[ " + className + " ", tagClass));
    }


    private static Element getBestElementFromFile(Element doc, final String url) {
        Element result = null;
        for( String line: PrefUtils.getString( PrefUtils.CONTENT_EXTRACT_RULES, R.string.full_text_root_default ).split( "\\n|\\s" ) ) {   //while ( result == null ) {
            if ( ( line == null ) || line.isEmpty() )
                continue;
            try {
                String[] list1 = line.split(":");
                String keyWord = list1[0];
                String[] list2 = list1[1].split("=");
                String elementType = list2[0].toLowerCase();
                String elementValue = list2[1];
                //if (doc.head().html().contains(keyWord)) {
                if (url.contains(keyWord)) {
                    if ( elementValue.equals( HANDY_NEWS_READER_ROOT_CLASS ) )
                        return doc;
                    if (elementType.equals("id"))
                        result = doc.getElementById(elementValue);
                    else if (elementType.equals("class")) {
                        final Elements elements = doc.getElementsByClass(elementValue);
                        if (!elements.isEmpty()) {
                            if ( elements.size() == 1 )
                                result = elements.first();
                            else {
                                for ( Element el: elements )
                                    el.attr(BEST_ELEMENT_ATTR, "1");
                                result = new Element("p");
                                result.insertChildren(0, elements.clone());
                            }
                        } else
                            result = doc;
                    }
                    break;
                }
            } catch ( Exception e ) {
                Dog.e( e.getMessage() );
            }

        }
        return result;
    }

    /**
     * Weights current element. By matching it with positive candidates and
     * weighting child nodes. Since it's impossible to predict which exactly
     * names, ids or class names will be used in HTML, major role is played by
     * child nodes
     *
     * @param e                Element to weight, along with child nodes
     * @param contentIndicator a text which should be included into the extracted content, or null
     */
    private static int getWeight(Element e, String contentIndicator) {
        int weight = calcWeight(e);
        weight += (int) Math.round(e.ownText().length() / 100.0 * 10);
        weight += weightChildNodes(e, contentIndicator);
        return weight;
    }

    /**
     * Weights a child nodes of given Element. During tests some difficulties
     * were met. For instance, not every single document has nested paragraph
     * tags inside of the major article tag. Sometimes people are adding one
     * more nesting level. So, we're adding 4 points for every 100 symbols
     * contained in tag nested inside of the current weighted element, but only
     * 3 points for every element that's nested 2 levels deep. This way we give
     * more chances to extract the element that has less nested levels,
     * increasing probability of the correct extraction.
     *
     * @param rootEl           Element, who's child nodes will be weighted
     * @param contentIndicator a text which should be included into the extracted content, or null
     */
    private static int weightChildNodes(Element rootEl, String contentIndicator) {
        int weight = 0;
        Element caption = null;
        List<Element> pEls = new ArrayList<>(5);
        for (Element child : rootEl.children()) {
            String text = child.text();
            int textLength = text.length();
            if (textLength < 20) {
                continue;
            }

            if (contentIndicator != null && text.contains(contentIndicator)) {
                weight += 100; // We certainly found the item
            }

            String ownText = child.ownText();
            int ownTextLength = ownText.length();
            if (ownTextLength > 200) {
                weight += Math.max(50, ownTextLength / 10);
            }

            if (child.tagName().equals("h1") || child.tagName().equals("h2")) {
                weight += 30;
            } else if (child.tagName().equals("div") || child.tagName().equals("p")) {
                weight += calcWeightForChild(ownText);
                if (child.tagName().equals("p") && textLength > 50)
                    pEls.add(child);

                if (child.className().toLowerCase().equals("caption"))
                    caption = child;
            }
        }

        // use caption and image
        if (caption != null)
            weight += 30;

        if (pEls.size() >= 2) {
            for (Element subEl : rootEl.children()) {
                if ("h1;h2;h3;h4;h5;h6".contains(subEl.tagName())) {
                    weight += 20;
                    // headerEls.add(subEl);
                }
            }
        }
        return weight;
    }

    private static int calcWeightForChild(String text) {
        return text.length() / 25;
//		return Math.min(100, text.length() / ((child.getAllElements().size()+1)*5));
    }

    private static int calcWeight(Element e) {
        int weight = 0;
        if (POSITIVE.matcher(e.className()).find())
            weight += 35;

        if (POSITIVE.matcher(e.id()).find())
            weight += 40;

        if (UNLIKELY.matcher(e.className()).find())
            weight -= 20;

        if (UNLIKELY.matcher(e.id()).find())
            weight -= 20;

        if (NEGATIVE.matcher(e.className()).find())
            weight -= 50;

        if (NEGATIVE.matcher(e.id()).find())
            weight -= 50;

        String style = e.attr("style");
        if (style != null && !style.isEmpty() && NEGATIVE_STYLE.matcher(style).find())
            weight -= 50;
        return weight;
    }

    /**
     * Prepares document. Currently only stipping unlikely candidates, since
     * from time to time they're getting more score than good ones especially in
     * cases when major text is short.
     *
     * @param doc document to prepare. Passed as reference, and changed inside
     *            of function
     */
    private static void prepareDocument(Element doc, MobilizeType mobilize) {
        // stripUnlikelyCandidates(doc);
        if ( mobilize == MobilizeType.Yes )
            removeSelectsAndOptions(doc);
        removeScriptsAndStyles(doc);
    }

    /**
     * Removes unlikely candidates from HTML. Currently takes id and class name
     * and matches them against list of patterns
     *
     * @param doc document to strip unlikely candidates from
     */
//    protected void stripUnlikelyCandidates(Document doc) {
//        for (Element child : doc.select("body").select("*")) {
//            String className = child.className().toLowerCase();
//            String id = child.id().toLowerCase();
//
//            if (NEGATIVE.matcher(className).find()
//                    || NEGATIVE.matcher(id).find()) {
//                child.remove();
//            }
//        }
//    }
    private static Element removeScriptsAndStyles(Element doc) {
        Elements scripts = doc.getElementsByTag("script");
        for (Element item : scripts) {
            item.remove();
        }

        if (FetcherService.isCancelRefresh())
            return doc;

        Elements noscripts = doc.getElementsByTag("noscript");
        for (Element item : noscripts) {
            item.remove();
        }

        if (FetcherService.isCancelRefresh())
            return doc;

        Elements styles = doc.getElementsByTag("style");
        for (Element style : styles) {
            style.remove();
        }

        return doc;
    }

    private static Element removeSelectsAndOptions(Element doc) {
        Elements scripts = doc.getElementsByTag("select");
        for (Element item : scripts) {
            item.remove();
        }

        if (FetcherService.isCancelRefresh())
            return doc;

        Elements noscripts = doc.getElementsByTag("option");
        for (Element item : noscripts) {
            item.remove();
        }

        return doc;
    }

    /**
     * @return a set of all meta nodes
     */
    private static Collection<Element> getMetas(Element doc) {
        Collection<Element> nodes = new HashSet<>(64);
        for (Element el : doc.select("head").select("meta")) {
            nodes.add(el);
        }
        return nodes;
    }

    /**
     * @return a set of all important nodes
     */
    private static Collection<Element> getNodes(Element doc) {
        Collection<Element> nodes = new HashSet<>(64);
        for (Element el : doc.select("body").select("*")) {
            if (NODES.matcher(el.tagName()).matches()) {
                nodes.add(el);
            }
        }
        return nodes;
    }
}

package dan2097.org.bitbucket.reactionextraction;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import uk.ac.cam.ch.wwmm.opsin.XOMTools;

import dan2097.org.bitbucket.paragraphclassification.ParagraphClassifier;
import dan2097.org.bitbucket.utility.ParagraphClassifierHolder;
import dan2097.org.bitbucket.utility.Utils;
import dan2097.org.bitbucket.utility.XMLAtrs;
import dan2097.org.bitbucket.utility.XMLTags;
import static dan2097.org.bitbucket.utility.ChemicalTaggerTags.*;

import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.Node;
import nu.xom.Nodes;

public class ExperimentalSectionsCreator {
	private static Logger LOG = Logger.getLogger(ExperimentalSectionsCreator.class);
	private static ParagraphClassifier paragraphClassifier = ParagraphClassifierHolder.getInstance();
	
	private final List<Element> orderedHeadingsAndParagraphs;
	private final List<ExperimentalSection> experimentalSections =new ArrayList<ExperimentalSection>();
	private ExperimentalSection currentSection = new ExperimentalSection();

	public ExperimentalSectionsCreator(List<Element> orderedHeadingsAndParagraphs) {
		this.orderedHeadingsAndParagraphs = orderedHeadingsAndParagraphs;
	}

	/**
	 * Attempts to return an experimental section for each example reaction.
	 * A multi step reaction should be contained within an experimental section
	 * @return
	 */
	public List<ExperimentalSection> createSections() {
		for (Element element : orderedHeadingsAndParagraphs) {
			boolean isHeading = isHeading(element);
			if (isHeading){
				handleHeading(element);
			}
			else{
				handleParagraph(element);
			}
		}
		addCurrentSectionIfNonEmptyAndReset();
		return experimentalSections;
	}

	boolean isHeading(Element headingOrParagraph) {
		String name = headingOrParagraph.getLocalName();
		if (name.equals(XMLTags.HEADING)){
			return true;
		}
		if (name.equals(XMLTags.P)){
			String id = headingOrParagraph.getAttributeValue(XMLAtrs.ID); 
			if (id !=null && id.startsWith("h-") && !headingOrParagraph.getValue().contains("\n")){
				return true;
			}
			else{
				return false;
			}
		}
		else{
			throw new IllegalArgumentException("Unexpected element local name: " + name);
		}
	}
	
	/**
	 * Extracts a procedures and/or molecule from the heading and adds it to the current section
	 * or step of the current section as a appropriate
	 * @param headingEl
	 */
	private void handleHeading(Element headingEl) {
		String text = Utils.getElementText(headingEl);
		Document taggedDoc = Utils.runChemicalTagger(text);
		List<String> namesFoundByOpsin = Utils.getSystematicChemicalNamesFromText(text);
		List<Element> procedureNames = extractProcedureNames(taggedDoc.getRootElement());
		if (namesFoundByOpsin.size()!=1 && procedureNames.size()!=1){
			//doesn't appear to be an appropriate heading
			addCurrentSectionIfNonEmptyAndReset();
			return;
		}
		boolean isPotentialSubHeading = isPotentialSubHeading(headingEl, taggedDoc.getRootElement());
		if (procedureNames.size()==1){
			addProcedure(procedureNames.get(0), isPotentialSubHeading);
		}
		if (namesFoundByOpsin.size()==1){
			String alias = TitleTextAliasExtractor.findAlias(text);
			String name = namesFoundByOpsin.get(0);
			ChemicalNameAliasPair nameAliasPair = new ChemicalNameAliasPair(name, alias);
			addNameAliasPair(nameAliasPair);
		}
	}

	/**
	 * Adds a procedure to an appropriate place in the current section or step
	 * @param procedure
	 * @param isPotentialSubHeading
	 */
	private void addProcedure(Element procedure, boolean isPotentialSubHeading) {
		if (currentSection.getProcedureElement()==null){
			currentSection.setProcedureElement(procedure);
		}
		else if (isPotentialSubHeading){
			if (currentSection.getCurrentStepProcedureElement()!=null){
				if (currentSection.currentStepHasParagraphs()){
					currentSection.moveToNextStep();
				}
				else{
					LOG.trace(currentSection.getCurrentStepProcedureElement().toXML() + " was discarded!");
				}
			}
			currentSection.setCurrentStepProcedure(procedure);
		}
		else{
			addCurrentSectionIfNonEmptyAndReset();
			currentSection.setProcedureElement(procedure);
		}
	}

	/**
	 * Adds a ChemicalNameAliasPair to an appropriate place in the current section or step
	 * @param nameAliasPair
	 */
	private void addNameAliasPair(ChemicalNameAliasPair nameAliasPair) {
		if (currentSection.currentStepHasParagraphs()){
			addCurrentSectionIfNonEmptyAndReset();
		}
		if (currentSection.getCurrentStepProcedureElement()!=null){
			if (currentSection.getCurrentStepTargetChemicalNamePair()!=null){
				LOG.trace(currentSection.getCurrentStepTargetChemicalNamePair() + " was discarded!");
			}
			currentSection.setCurrentStepTargetChemicalNamePair(nameAliasPair);
		}
		else{
			if (currentSection.getTargetChemicalNamePair()!=null){
				LOG.trace(currentSection.getTargetChemicalNamePair() + " was discarded!");
			}
			currentSection.setTargetChemicalNamePair(nameAliasPair);
		}
	}

	private List<Element> extractProcedureNames(Element taggedDocRoot) {
		return XOMTools.getDescendantElementsWithTagName(taggedDocRoot, PROCEDURE_Container);
	}
	
	/**
	 * Could the given heading be a sub heading
	 * @param heading
	 * @param taggedDocRoot 
	 * @return
	 */
	boolean isPotentialSubHeading(Element heading, Element taggedDocRoot) {
		if (heading.getLocalName().equals(XMLTags.P)){
			String id = heading.getAttributeValue(XMLAtrs.ID); 
			if (id !=null && id.startsWith("h-")){
				return true;
			}
		}
		for (Element method : XOMTools.getDescendantElementsWithTagName(taggedDocRoot, NN_METHOD)) {
			if (method.getValue().equalsIgnoreCase("step")){
				return true;
			}
		}
		return false;
	}

	/**
	 * Examines paragraph for a starting procedure/chemical name heading
	 * Then adds pargagraph to the current step
	 * @param paraEl
	 */
	private void handleParagraph(Element paraEl) {
		String text = Utils.detachIrrelevantElementsAndGetParagraphText(paraEl);
		if (text.equals("")){//blank paragraph
			return;
		}
		boolean isExperimentalParagraph = paragraphClassifier.isExperimental(text);
		if (!isExperimentalParagraph){
			addCurrentSectionIfNonEmptyAndReset();
			return;
		}
		Paragraph para = new Paragraph(text);
		if (para.getTaggedString().equals("")){//blank paragraph
			return;
		}
		
		//Sometimes headings are present at the start of paragraphs...
		Element hiddenHeadingEl = findAndDetachHiddenHeadingContent(para.getTaggedSentencesDocument());
		if (hiddenHeadingEl !=null){
			processInlineHeading(hiddenHeadingEl, paraEl, text);
		}

		if (currentSection.getProcedureElement()==null && currentSection.getCurrentStepProcedureElement()==null 
				&& currentSection.getTargetChemicalNamePair()==null && currentSection.getCurrentStepTargetChemicalNamePair()==null ){//typically experimental paragraphs are preceded by a suitable heading
			addCurrentSectionIfNonEmptyAndReset();
			if (isSelfStandingParagraph(para.getTaggedSentencesDocument())){
				currentSection.addParagraphToCurrentStep(para);
			}
			//non self standing paragraphs with no heading are discarded
		}
		else{
			currentSection.addParagraphToCurrentStep(para);
		}
	}

	/**
	 * Similar in function to processHeading except hiddenHeadingEl has already been tagged by chemical tagger
	 * There is a possibility of the heading being a false positive e.g. "LCMS:" so a new section is not started if
	 * this method fails to do anything
	 * @param hiddenHeadingEl
	 * @param text 
	 * @param paraEl 
	 */
	private void processInlineHeading(Element hiddenHeadingEl, Element paraEl, String text) {
		String headingText = findTextCorrespondingToChemicallyTaggedText(hiddenHeadingEl, text);
		List<String> namesFoundByOpsin = Utils.getSystematicChemicalNamesFromText(headingText);
		List<Element> procedureNames = extractProcedureNames(hiddenHeadingEl);
		boolean isPotentialSubHeading = isPotentialSubHeading(paraEl, hiddenHeadingEl);
		if (procedureNames.size()==1){
			addProcedure(procedureNames.get(0), isPotentialSubHeading);
		}
		if (namesFoundByOpsin.size()==1){
			String alias = TitleTextAliasExtractor.findAlias(headingText);
			String name = namesFoundByOpsin.get(0);
			ChemicalNameAliasPair nameAliasPair = new ChemicalNameAliasPair(name, alias);
			addNameAliasPair(nameAliasPair);
		}
	}

	/**
	 * Find the text corresponding to chemical tagger's input.
	 * This is non trivial as chemical tagger does not employ a pure white space tokenizer
	 * @param hiddenHeadingEl
	 * @param text
	 * @return
	 */
	private String findTextCorrespondingToChemicallyTaggedText(Element taggedTextEl, String text) {
		String extractedText = Utils.getElementText(taggedTextEl);
		char[] extractedCharArray = extractedText.toCharArray();
		char[] inputCharArray = text.replaceAll("sulph", "sulf").toCharArray();
		StringBuilder sb = new StringBuilder();
		for (int i = 0, j=0; i < inputCharArray.length && j < extractedCharArray.length; i++, j++) {
			if (inputCharArray[i] == ' '){
				j--;
			}
			sb.append(inputCharArray[i]);
		}
		return sb.toString().trim();
	}

	/**
	 * Looks for known patterns that are indicative of headings
	 * Detaches the heading element/s and returns them attached to a new heading element
	 * @param taggedDoc
	 * @return
	 */
	Element findAndDetachHiddenHeadingContent(Document taggedDoc) {
		Element firstSentence = taggedDoc.getRootElement().getFirstChildElement(SENTENCE_Container);
		Element heading = null;
		if (firstSentence != null){
			List<Element> elementsToConsider = expandActionPhrases(firstSentence.getChildElements());
			if (elementsToConsider.size() >=2){
				Element firstPhrase =elementsToConsider.get(0);
				if (firstPhrase.getLocalName().equals(NOUN_PHRASE_Container) && nounphraseContainsRecognisedHeadingForm(firstPhrase)){
					Element secondPhrase = elementsToConsider.get(1);
					if (isPeriodOrSemiColonOrColon(secondPhrase)){
						heading = new Element(XMLTags.HEADING);
						detachElementAndEmptyActionPhraseParents(firstPhrase);
						detachElementAndEmptyActionPhraseParents(secondPhrase);
						heading.appendChild(firstPhrase);
						heading.appendChild(secondPhrase);
						if(elementsToConsider.size() >=4){
							Element thirdPhrase =elementsToConsider.get(2);
							Element fourthPhrase =elementsToConsider.get(3);
							if (thirdPhrase.getLocalName().equals(NOUN_PHRASE_Container) 
									&& nounphraseContainsRecognisedHeadingForm(thirdPhrase)
									&& isPeriodOrSemiColonOrColon(fourthPhrase)){
								detachElementAndEmptyActionPhraseParents(thirdPhrase);
								detachElementAndEmptyActionPhraseParents(fourthPhrase);
								heading.appendChild(thirdPhrase);
								heading.appendChild(fourthPhrase);
							}
						}
					}
				}
			}
		}
		return heading;
	}

	private void detachElementAndEmptyActionPhraseParents(Element element) {
		Element parent = (Element) element.getParent();
		element.detach();
		while (parent.getLocalName().equals(ACTIONPHRASE_Container) && parent.getChildElements().size()==0){
			Node newParent = parent.getParent();
			parent.detach();
			if (!(newParent instanceof Element)){
				break;
			}
			parent = (Element) newParent;
		}
	}

	/**
	 * Returns the list of children with action phrases recursively replaced by their children
	 * @param children
	 * @return
	 */
	private List<Element> expandActionPhrases(Elements children) {
		List<Element> elements = new ArrayList<Element>();
		for (int i = 0; i < children.size(); i++) {
			Element child = children.get(i);
			if (child.getLocalName().equals(ACTIONPHRASE_Container)){
				elements.addAll(expandActionPhrases(child.getChildElements()));
			}
			else{
				elements.add(child);
			}
		}
		return elements;
	}

	/**
	 * Does the nounPhrase contain a recognised heading form
	 * @param nounPhrase
	 * @return
	 */
	private boolean nounphraseContainsRecognisedHeadingForm(Element nounPhrase) {
		Elements nounPhraseChildren = nounPhrase.getChildElements();
		if (nounPhraseChildren.size()==1){
			String child1Lc = nounPhraseChildren.get(0).getLocalName();
			if (child1Lc.equals(MOLECULE_Container) || child1Lc.equals(PROCEDURE_Container) || child1Lc.equals(UNNAMEDMOLECULE_Container)){
				return true;
			}
		}
		else if (nounPhraseChildren.size()==2){
			String child1Lc = nounPhraseChildren.get(0).getLocalName();
			String child2Lc = nounPhraseChildren.get(1).getLocalName();
			if ((child1Lc.equals(UNNAMEDMOLECULE_Container) || child1Lc.equals(PROCEDURE_Container) )
					&& child2Lc.equals(MOLECULE_Container)){
				return true;
			}
			
			if (child1Lc.equals(NN_SYNTHESIZE) && child2Lc.equals(PREPPHRASE_Container) && isAnOfNounPhrasePrepPhrase(nounPhraseChildren.get(1))){
				return true;
			}
		}
		else if (nounPhraseChildren.size()==3){
			String child1Lc = nounPhraseChildren.get(0).getLocalName();
			String child2Lc = nounPhraseChildren.get(1).getLocalName();
			String child3Lc = nounPhraseChildren.get(2).getLocalName();
			if ((child1Lc.equals(UNNAMEDMOLECULE_Container) || child1Lc.equals(PROCEDURE_Container) )
					&& child2Lc.equals(NN_SYNTHESIZE) && child3Lc.equals(PREPPHRASE_Container) && isAnOfNounPhrasePrepPhrase(nounPhraseChildren.get(2))){
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Does this prepphrase contain IN-OF NOUNPHRASE
	 * @param prepPhrase
	 * @return
	 */
	private boolean isAnOfNounPhrasePrepPhrase(Element prepPhrase) {
		Elements childrenOfPrepPhrase = prepPhrase.getChildElements();
		if (childrenOfPrepPhrase.size()==2){
			String child1Lc = childrenOfPrepPhrase.get(0).getLocalName();
			String child2Lc = childrenOfPrepPhrase.get(1).getLocalName();
			if (child1Lc.equals(IN_OF) && child2Lc.equals(NOUN_PHRASE_Container)){
				return true;
			}
		}
		return false;
	}

	/**
	 * Is the value of this element a period/semicolon/colon
	 * @param secondPhrase
	 * @return
	 */
	private boolean isPeriodOrSemiColonOrColon(Element el) {
		String value = el.getValue();
		return (value.equals(".") || value.equals(";") || value.equals(":"));
	}

	/**
	 * Checks whether the document has a resolvable chemical that is indicated to have been yielded
	 * @param taggedDoc
	 * @return
	 */
	boolean isSelfStandingParagraph(Document taggedDoc) {
		Nodes yieldNodes = taggedDoc.query("//ActionPhrase[@type='Yield']//MOLECULE");
		for (int i = 0; i < yieldNodes.size(); i++) {
			Element molecule = (Element) yieldNodes.get(i);
			String smiles = Utils.resolveNameToSmiles(ChemTaggerOutputNameExtraction.findMoleculeName(molecule));
			if (smiles != null){
				return true;
			}
		}
		return false;
	}

	/**
	 * Adds the currentSection to experimentalSections if it contains experimental paragraphs
	 */
	private void addCurrentSectionIfNonEmptyAndReset() {
		addCurrentSectionIfNonEmpty();
		currentSection = new ExperimentalSection();
	}

	private void addCurrentSectionIfNonEmpty() {
		List<ExperimentalStep> steps = currentSection.getExperimentalSteps();
		if (steps.size()>0){
			ExperimentalStep lastStep = steps.get(steps.size()-1);
			if (lastStep.getParagraphs().size()==0){
				steps.remove(lastStep);
			}
			if (!steps.isEmpty()){
				experimentalSections.add(currentSection);
			}
		}
	}

}
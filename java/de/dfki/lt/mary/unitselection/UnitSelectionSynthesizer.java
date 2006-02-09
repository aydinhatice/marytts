/**
 * Copyright 2006 DFKI GmbH.
 * All Rights Reserved.  Use is subject to license terms.
 * 
 * Permission is hereby granted, free of charge, to use and distribute
 * this software and its documentation without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of this work, and to
 * permit persons to whom this work is furnished to do so, subject to
 * the following conditions:
 * 
 * 1. The code must retain the above copyright notice, this list of
 *    conditions and the following disclaimer.
 * 2. Any modifications must be clearly marked as such.
 * 3. Original authors' names are not deleted.
 * 4. The authors' names are not used to endorse or promote products
 *    derived from this software without specific prior written
 *    permission.
 *
 * DFKI GMBH AND THE CONTRIBUTORS TO THIS WORK DISCLAIM ALL WARRANTIES WITH
 * REGARD TO THIS SOFTWARE, INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS, IN NO EVENT SHALL DFKI GMBH NOR THE
 * CONTRIBUTORS BE LIABLE FOR ANY SPECIAL, INDIRECT OR CONSEQUENTIAL
 * DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR
 * PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS
 * ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
 * THIS SOFTWARE.
 */
package de.dfki.lt.mary.unitselection;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;

import javax.sound.sampled.AudioInputStream;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.w3c.dom.Element;
import org.w3c.dom.traversal.DocumentTraversal;
import org.w3c.dom.traversal.NodeFilter;
import org.w3c.dom.traversal.TreeWalker;

import com.sun.speech.freetts.Utterance;

import de.dfki.lt.mary.MaryData;
import de.dfki.lt.mary.MaryDataType;
import de.dfki.lt.mary.MaryProperties;
import de.dfki.lt.mary.MaryXML;
import de.dfki.lt.mary.modules.synthesis.SynthesisException;
import de.dfki.lt.mary.modules.synthesis.Voice;
import de.dfki.lt.mary.modules.synthesis.WaveformSynthesizer;
import de.dfki.lt.mary.util.dom.NameNodeFilter;

/**
 * Builds and synthesizes unit selection voices
 * 
 * @author Marc Schr&ouml;der, Anna Hunecke
 *
 */

public class UnitSelectionSynthesizer implements WaveformSynthesizer
{
    /**
     * A map with Voice objects as keys, and Lists of UtteranceProcessors as values.
     * Idea: For a given voice, find the list of utterance processors to apply. 
     */
    private Logger logger;

    protected UnitSelector unitSelector;
    protected UnitConcatenator unitConcatenator;

    public UnitSelectionSynthesizer() {}
    
    
    /** 
     * Start up the waveform synthesizer. This must be called once before
     * calling synthesize(). 
     */
    public void startup() throws Exception
    {
        logger = Logger.getLogger("UnitSelectionSynthesizer");
        // Register UnitSelection voices:
        logger.debug("Register UnitSelection voices:");
        String voiceNames = MaryProperties.getProperty("unitselection.voices.list");
        if (voiceNames != null) { // voices present
            UnitSelectionVoiceBuilder voiceBuilder = new UnitSelectionVoiceBuilder(this);
            for (StringTokenizer st = new StringTokenizer(voiceNames); st.hasMoreTokens(); ) {
                String voiceName = st.nextToken();
                //take the time
                long time = System.currentTimeMillis();
                Voice unitSelVoice = voiceBuilder.buildVoice(voiceName);
                logger.debug("Voice '" + unitSelVoice + "'");
                Voice.registerVoice(unitSelVoice);    
                long newtime = System.currentTimeMillis()-time;
                logger.info("Loading of voice "+voiceName+" took "+newtime+" milliseconds");
            } 
        }
        logger.info("started.");
    }

    /**
      * Perform a power-on self test by processing some example input data.
      * @throws Error if the module does not work properly.
      */
     public void powerOnSelfTest() throws Error
     {
        try {
            MaryData in = new MaryData(MaryDataType.get("ACOUSTPARAMS"));
            List myVoices = Voice.getAvailableVoices(this);
            if (myVoices.size() == 0) {
                return;
            }
            UnitSelectionVoice unitSelVoice = (UnitSelectionVoice) myVoices.get(0);
            assert unitSelVoice != null;
            if (!unitSelVoice.getDomain().equals("general")) {
                logger.info("Cannot perform power-on self test using limited-domain voice '" + unitSelVoice.getName() + "' - skipping.");
                return;
            }
            String exampleText;
            if (unitSelVoice.getLocale().equals(Locale.GERMAN)) {
                exampleText = MaryDataType.get("ACOUSTPARAMS_DE").exampleText();
            } else {
                exampleText = MaryDataType.get("ACOUSTPARAMS_EN").exampleText();
            }
            in.readFrom(new StringReader(exampleText));
            in.setDefaultVoice(unitSelVoice);
            if (in == null){
                System.out.println(exampleText+" is null");}
            List tokensAndBoundaries = new ArrayList();
            TreeWalker tw = ((DocumentTraversal)in.getDocument()).createTreeWalker(
                    in.getDocument(), NodeFilter.SHOW_ELEMENT,
                    new NameNodeFilter(new String[] {MaryXML.TOKEN, MaryXML.BOUNDARY}),
                    false);
            Element el = null;
            while ((el = (Element)tw.nextNode()) != null)
                tokensAndBoundaries.add(el);
            AudioInputStream ais = synthesize(tokensAndBoundaries, unitSelVoice);
            assert ais != null;
        } catch (Throwable t) {
            t.printStackTrace();
            throw new Error("Module " + toString() + ": Power-on self test failed.", t);
        }
        logger.info("Power-on self test complete.");
    }
        
     private void verifyDebugLog(Utterance utt, String moduleName)
     {
         if (logger.getEffectiveLevel().equals(Level.DEBUG)) {
             StringWriter sw = new StringWriter();
             PrintWriter pw = new PrintWriter(sw);
             utt.dump(pw, 2, moduleName, true); // padding, justRelations
             logger.debug("Constructed the following Utterance:");
             logger.debug(sw.toString());
         }

     }
     
     

    /**
     * Synthesize a given part of a MaryXML document. This method is expected
     * to be thread-safe.
     * @param tokensAndBoundaries the part of the MaryXML document to
     * synthesize; a list containing a number of adjacent <t> and <boundary>
     * elements.
     * @return an AudioInputStream in synthesizer-native audio format.
     * @throws IllegalArgumentException if the voice requested for this section
     * is incompatible with this WaveformSynthesizer.
     */
    public AudioInputStream synthesize(List tokensAndBoundaries, Voice voice)
        throws SynthesisException
    {
        assert voice instanceof UnitSelectionVoice;
        // Select:
        UnitSelector unitSel = ((UnitSelectionVoice)voice).getUnitSelector();
        UnitDatabase database = ((UnitSelectionVoice)voice).getDatabase();
        logger.debug("Selecting units with a "+unitSel.getClass().getName()+" from a "+database.getClass().getName());
        List selectedUnits = unitSel.selectUnits(tokensAndBoundaries, voice, database, ((UnitSelectionVoice)voice).getUnitNamer());
        //if (logger.getEffectiveLevel().equals(Level.DEBUG)) {
          //  StringWriter sw = new StringWriter();
           // PrintWriter pw = new PrintWriter(sw);
            //for (Iterator selIt=selectedUnits.iterator(); selIt.hasNext(); )
              //  pw.println(selIt.next());
            //logger.debug("Units selected:\n"+sw.toString());
        //}
        // Concatenate:
        unitConcatenator = ((UnitSelectionVoice) voice).getConcatenator();
        logger.debug("Now creating audio with a "+unitConcatenator.getClass().getName());
        return unitConcatenator.getAudio(selectedUnits);
    }
    
}

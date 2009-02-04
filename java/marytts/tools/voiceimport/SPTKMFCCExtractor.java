package marytts.tools.voiceimport;

import java.io.*;
import java.util.*;

import marytts.cart.*;
import marytts.cart.LeafNode.FeatureVectorLeafNode;
import marytts.cart.LeafNode.FloatLeafNode;
import marytts.cart.LeafNode.IntAndFloatArrayLeafNode;
import marytts.cart.LeafNode.IntArrayLeafNode;
import marytts.cart.LeafNode.LeafType;
import marytts.cart.io.MaryCARTReader;
import marytts.cart.io.MaryCARTWriter;
import marytts.features.FeatureDefinition;
import marytts.features.FeatureVector;
import marytts.signalproc.analysis.Mfccs;
import marytts.unitselection.data.Datagram;
import marytts.unitselection.data.FeatureFileReader;
import marytts.unitselection.data.MCepDatagram;
import marytts.unitselection.data.MCepTimelineReader;
import marytts.unitselection.data.UnitFileReader;
import marytts.util.MaryUtils;


/**
 * A component for extracting mfcc files from wave files 
 * @author Marcela, Sathish Pammi
 *
 */
public class SPTKMFCCExtractor extends VoiceImportComponent {

    public final String SPTKINST = "SPTKMFCCExtractor.sptkinstallationDir";
    public final String SOXCOMMAND = "SPTKMFCCExtractor.soxCommand";
    public final String INWAVDIR = "SPTKMFCCExtractor.inputWAVEDIR";
    public final String OUTMFCCDIR = "SPTKMFCCExtractor.outputMFCCDIR";
    private DatabaseLayout db;    
    private String sCostDirectory;
    private String mfccExt = ".mfcc";
    protected int percent = 0;
    
    protected void setupHelp() {
        props2Help = new TreeMap();
        props2Help.put(SOXCOMMAND,"Sox binary file absolute path.  ex: /usr/bin/sox ");
        props2Help.put(SPTKINST,"SPTK installation directory. ex: /home/user/sw/SPTK-3.1/"); 
        props2Help.put(INWAVDIR, "Input wave directory");
        props2Help.put(OUTMFCCDIR, "Output MFCC directory");
    }
    
    public SortedMap<String, String> getDefaultProps(DatabaseLayout db) {
        this.db = db;
        if (props == null){
            props = new TreeMap();
            props.put(SOXCOMMAND,"/usr/bin/sox");
            props.put(SPTKINST,"/home/user/sw/SPTK-3.1/");
            props.put(INWAVDIR, db.getProp(db.ROOTDIR)+File.separator+db.getProp(db.WAVDIR));
            props.put(OUTMFCCDIR, db.getProp(db.ROOTDIR)+File.separator+"sCost"+File.separator+"wavemfcc");
        }
        return props;
    }

    public final String getName() {
        return "SPTKMFCCExtractor";
    }
    
    public void initialiseComp(){
        // sCost dir creation, if doesn't exists
        sCostDirectory = db.getProp(db.ROOTDIR)+File.separator+"sCost";
        File sCostDir =  new File(sCostDirectory);
        if(!sCostDir.exists()){
            System.out.print(sCostDir.getAbsolutePath()+" does not exist; ");
            if (!sCostDir.mkdir()){
                throw new Error("Could not create "+sCostDir.getAbsolutePath());
            }
            System.out.print("Created successfully.\n");
        }
    }

    public boolean compute() throws Exception {
        
        // check for SOX
        File soxFile = new File(getProp(SOXCOMMAND));
        if (!soxFile.exists()) {
            throw new IOException("SOX command setting is wrong. Because file "+soxFile.getAbsolutePath()+" does not exist");
        }
        
        // check for SPTK
        File sptkFile = new File(getProp(SPTKINST)+File.separator+"bin"+File.separator+"mcep");
        if (!sptkFile.exists()) {
            throw new IOException("SPTK path setting is wrong. Because file "+sptkFile.getAbsolutePath()+" does not exist");
        }
        
        // output mfcc dir creator 
        File waveMFCCDir =  new File(getProp(OUTMFCCDIR));
        if(!waveMFCCDir.exists()){
            System.out.print(waveMFCCDir.getAbsolutePath()+" does not exist; ");
            if (!waveMFCCDir.mkdir()){
                throw new Error("Could not create "+waveMFCCDir.getAbsolutePath());
            }
            System.out.print("Created successfully.\n");
        }
        
        // Now process all files, one by one
        for (int i=0; i<bnl.getLength(); i++) {
            percent = 100*i/bnl.getLength();
            String baseName = bnl.getName(i);
            String inFile = getProp(INWAVDIR)+File.separator+baseName+db.getProp(db.WAVEXT);
            String outFile = getProp(OUTMFCCDIR)+File.separator+baseName+mfccExt;
            getSptkMfcc(inFile,outFile);
        }
        
        return true;
    }


    
    
    public int getProgress() {
        return percent;
    }

    /***
     * Calculate mfcc using SPTK, uses sox to convert wav-->raw
     * @throws IOException
     * @throws InterruptedException
     * @throws Exception
     */
    public void getSptkMfcc(String inFile, String outFile) throws IOException, InterruptedException, Exception{
       
       String tmpFile = db.getProp(db.TEMPDIR)+File.separator+"tmp.mfc"; 
       String tmpRawFile = db.getProp(db.TEMPDIR)+File.separator+"tmp.raw";
       String cmd;
        
       // SPTK parameters
       int fs = 16000;
       int frameLength = 400;
       int frameLengthOutput = 512;
       int framePeriod = 80;
       int mgcOrder = 24;
       int mgcDimension = 25;
       // Mary header parameters
       double ws = (frameLength/fs);   // window size in seconds 
       double ss = (framePeriod/fs);   // skip size in seconds
       
       // SOX and SPTK commands
       String sox = getProp(SOXCOMMAND);
       String x2x = " "+getProp(SPTKINST)+File.separator+"/bin/x2x";
       String frame = " "+getProp(SPTKINST)+File.separator+"/bin/frame";
       String window = " "+getProp(SPTKINST)+File.separator+"/bin/window";
       String mcep = " "+getProp(SPTKINST)+File.separator+"/bin/mcep";
       String swab = " "+getProp(SPTKINST)+File.separator+"/bin/swab";
       
      
       // convert the wav file to raw file with sox
       cmd = sox + " " + inFile + " " +  tmpRawFile;
       launchProc( cmd, "sox", inFile);
       
       System.out.println("Extracting MGC coefficients from " + inFile);
       
       cmd = x2x + " +sf " + tmpRawFile + " | " +
                    frame + " +f -l " + frameLength + " -p " + framePeriod + " | " +
                    window + " -l " + frameLength + " -L " + frameLengthOutput + " -w 1 -n 1 | " +
                    mcep + " -a 0.42 -m " + mgcOrder + "  -l " + frameLengthOutput + " | " +
                    swab + " +f > " + tmpFile;
       
       System.out.println("cmd=" + cmd);
       launchBatchProc( cmd, "getSptkMfcc", inFile );
       
       // Now get the data and add the Mary header
       int numFrames;
       DataInputStream mfcData=null;
       Vector <Float> mfc = new Vector<Float>();
      
       mfcData = new DataInputStream (new BufferedInputStream(new FileInputStream(tmpFile)));
       try {
         while(true){
           mfc.add(mfcData.readFloat());
         }         
       } catch (EOFException e) { }
       mfcData.close();

       numFrames = mfc.size();
       int numVectors = numFrames/mgcDimension;
       Mfccs mgc = new Mfccs(numVectors, mgcDimension);
       
       int k=0;
       for(int i=0; i<numVectors; i++){
         for(int j=0; j<mgcDimension; j++){
           mgc.mfccs[i][j] = mfc.get(k);
           k++;
         }
       }
       // Mary header parameters
       mgc.params.samplingRate = fs;         /* samplingRateInHz */
       mgc.params.skipsize     = (float)ss;  /* skipSizeInSeconds */
       mgc.params.winsize      = (float)ws;  /* windowSizeInSeconds */
       
       mgc.writeMfccFile(outFile);

       // remove temp files
       // 1. tempmfcc file
       File tempFile = new File(tmpFile);
       if(tempFile.exists()){
           tempFile.delete();
       }
       // 2. temp raw file
       tempFile = new File(tmpRawFile);
       if(tempFile.exists()){
           tempFile.delete();
       }
    }
    
    
    /**
     * A general process launcher for the various tasks
     * (copied from ESTCaller.java)
     * @param cmdLine the command line to be launched.
     * @param task a task tag for error messages, such as "Pitchmarks" or "LPC".
     * @param the basename of the file currently processed, for error messages.
     */
    private void launchProc( String cmdLine, String task, String baseName ) {
        
        Process proc = null;
        BufferedReader procStdout = null;
        String line = null;
        try {
           proc = Runtime.getRuntime().exec( cmdLine );
            
            /* Collect stdout and send it to System.out: */
            procStdout = new BufferedReader( new InputStreamReader( proc.getInputStream() ) );
            while( true ) {
                line = procStdout.readLine();
                if ( line == null ) break;
                System.out.println( line );
            }
            /* Wait and check the exit value */
            proc.waitFor();
            if ( proc.exitValue() != 0 ) {
                throw new RuntimeException( task + " computation failed on file [" + baseName + "]!\n"
                        + "Command line was: [" + cmdLine + "]." );
            }
        }
        catch ( IOException e ) {
            throw new RuntimeException( task + " computation provoked an IOException on file [" + baseName + "].", e );
        }
        catch ( InterruptedException e ) {
            throw new RuntimeException( task + " computation interrupted on file [" + baseName + "].", e );
        }
        
    }    

    
    /**
     * A general process launcher for the various tasks but using an intermediate batch file
     * (copied from ESTCaller.java)
     * @param cmdLine the command line to be launched.
     * @param task a task tag for error messages, such as "Pitchmarks" or "LPC".
     * @param the basename of the file currently processed, for error messages.
     */
    private void launchBatchProc( String cmdLine, String task, String baseName ) {
        
        Process proc = null;
        Process proctmp = null;
        BufferedReader procStdout = null;
        String line = null;
        String tmpFile = "./tmp.bat";
       
        try {
            FileWriter tmp = new FileWriter(tmpFile);
            tmp.write(cmdLine);
            tmp.close();
            
            /* make it executable... */
            proctmp = Runtime.getRuntime().exec( "chmod +x "+tmpFile );
            proctmp.waitFor();
            proc = Runtime.getRuntime().exec( tmpFile );
            
            /* Collect stdout and send it to System.out: */
            procStdout = new BufferedReader( new InputStreamReader( proc.getInputStream() ) );
            while( true ) {
                line = procStdout.readLine();
                if ( line == null ) break;
                System.out.println( line );
            }
            /* Wait and check the exit value */
            proc.waitFor();
            if ( proc.exitValue() != 0 ) {
                throw new RuntimeException( task + " computation failed on file [" + baseName + "]!\n"
                        + "Command line was: [" + cmdLine + "]." );
            }
                       
        }
        catch ( IOException e ) {
            throw new RuntimeException( task + " computation provoked an IOException on file [" + baseName + "].", e );
        }
        catch ( InterruptedException e ) {
            throw new RuntimeException( task + " computation interrupted on file [" + baseName + "].", e );
        }
        
    }     

}

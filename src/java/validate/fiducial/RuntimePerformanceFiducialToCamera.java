package validate.fiducial;

import boofcv.abst.fiducial.FiducialDetector;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.io.image.UtilImageIO;
import boofcv.struct.calib.IntrinsicParameters;
import boofcv.struct.image.ImageBase;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author Peter Abeles
 */
public class RuntimePerformanceFiducialToCamera< T extends ImageBase> {

	PrintStream outputResults = System.out;
	PrintStream err = System.err;

	int numFrames = 300;

	BaseEstimateSquareFiducialToCamera<T> factory;


	public RuntimePerformanceFiducialToCamera(BaseEstimateSquareFiducialToCamera<T> factory) {
		this.factory = factory;
	}

	public void evaluate( File inputDirectory ) {
		List<File> files = Arrays.asList(inputDirectory.listFiles());
		Collections.sort(files);

		for( File f : files ) {
			if( f.isDirectory() ) {
				evaluateScenario(f);
			}
		}
	}

	private void evaluateScenario( File inputDirectory ) {

		List<T> frames = new ArrayList<T>();

		IntrinsicParameters intrinsic = FiducialCommon.parseIntrinsic(new File(inputDirectory,"intrinsic.txt"));

		FiducialDetector<T> detector = factory.createDetector(inputDirectory);
		detector.setIntrinsic(intrinsic);

		File[] files = inputDirectory.listFiles();
		for( File f : files ) {
			BufferedImage image = UtilImageIO.loadImage(f.getPath());
			if( image == null )
				continue;

			T frame = ConvertBufferedImage.convertFrom(image,true,detector.getInputType());
			frames.add( frame );
		}

		long startTime = System.currentTimeMillis();
		for (int trial = 0; trial < numFrames; trial++) {
			detector.detect(frames.get(trial % frames.size()));
		}
		long endTime = System.currentTimeMillis();

		double fps = numFrames/((endTime-startTime)/1000.0);

		outputResults.printf("%s %d %7.3f\n",inputDirectory.getPath(),numFrames,fps);
	}

	public void setOutputResults(PrintStream outputResults) {
		this.outputResults = outputResults;
	}

	public void setErrorStream(PrintStream err) {
		this.err = err;
	}

}
package boofcv.regression;

import boofcv.abst.feature.describe.DescribeRegionPoint;
import boofcv.abst.feature.detdesc.DetectDescribeMulti;
import boofcv.abst.feature.detdesc.DetectDescribeMultiFusion;
import boofcv.abst.feature.detect.extract.ConfigExtract;
import boofcv.abst.feature.detect.extract.NonMaxSuppression;
import boofcv.abst.feature.detect.intensity.GeneralFeatureIntensity;
import boofcv.abst.feature.detect.interest.ConfigGeneralDetector;
import boofcv.abst.feature.detect.interest.DetectorInterestPointMulti;
import boofcv.abst.feature.detect.interest.GeneralToInterestMulti;
import boofcv.abst.feature.disparity.StereoDisparitySparse;
import boofcv.abst.feature.tracker.PointTracker;
import boofcv.abst.feature.tracker.PointTrackerTwoPass;
import boofcv.abst.sfm.d3.StereoVisualOdometry;
import boofcv.alg.feature.detect.interest.GeneralFeatureDetector;
import boofcv.alg.filter.derivative.GImageDerivativeOps;
import boofcv.alg.tracker.klt.PkltConfig;
import boofcv.common.BaseImageRegression;
import boofcv.common.BoofRegressionConstants;
import boofcv.factory.feature.describe.FactoryDescribeRegionPoint;
import boofcv.factory.feature.detect.extract.FactoryFeatureExtractor;
import boofcv.factory.feature.detect.intensity.FactoryIntensityPoint;
import boofcv.factory.feature.disparity.FactoryStereoDisparity;
import boofcv.factory.feature.tracker.FactoryPointTracker;
import boofcv.factory.feature.tracker.FactoryPointTrackerTwoPass;
import boofcv.factory.sfm.FactoryVisualOdometry;
import boofcv.metrics.vo.*;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.ImageDataType;
import boofcv.struct.image.ImageType;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Peter Abeles
 */
public class StereoVisualOdometryRegression extends BaseImageRegression {

	@Override
	public void process(ImageDataType type) throws IOException {
		List<Info> all = new ArrayList<Info>();

		Class bandType = ImageDataType.typeToSingleClass(type);

		all.add( createDepth(bandType));
		all.add( createDualTrackerPnP(bandType));
		all.add( createQuadPnP(bandType));

		for( Info a : all ) {
			try {
				SequenceStereoImages data = new WrapParseLeuven07(new ParseLeuven07("data/leuven07"));
				evaluate(a,data,"Leuven07");
				for( int i = 0; i < 1; i++ ) { // can do up to 11
					String sequence = String.format("%02d",i);
					data = new WrapParseKITTI("data/KITTI",sequence);
					evaluate(a,data,"KITTI"+sequence);
				}
			} catch( RuntimeException e ) {
				errorLog.println("FAILED to process "+a.name);
				e.printStackTrace(errorLog);
				errorLog.println("---------------------------------------------------");
			}
		}
	}

	private void evaluate( Info vo , SequenceStereoImages data , String dataName ) throws FileNotFoundException {
		PrintStream out = new PrintStream(new File(directory,"StereoVisOdom_"+dataName+"_"+vo.name+".txt"));
		try {
			EvaluateVisualOdometryStereo evaluator = new EvaluateVisualOdometryStereo(data,vo.vo,vo.imageType);

			evaluator.setOutputStream(out);
			evaluator.initialize();
			while( evaluator.nextFrame() ){}
		} catch( RuntimeException e ) {
			errorLog.println("FAILED "+vo.name+" on "+dataName);
			e.printStackTrace(errorLog);
			errorLog.println("---------------------------------------------------");
		}
	}

	public static Info createDepth( Class bandType ) {
		Class derivType = GImageDerivativeOps.getDerivativeType(bandType);

		StereoDisparitySparse<GrayF32> disparity =
				FactoryStereoDisparity.regionSparseWta(10, 120, 2, 2, 30, 0.1, true, bandType);

		PkltConfig configKlt = new PkltConfig();
		configKlt.pyramidScaling = new int[]{1, 2, 4, 8};
		configKlt.templateRadius = 3;

		PointTrackerTwoPass tracker = FactoryPointTrackerTwoPass.klt(configKlt, new ConfigGeneralDetector(600, 3, 1),
				bandType, derivType);

		Info ret = new Info();
		ret.name = "StereoDepth";
		ret.imageType = ImageType.single(bandType);
		ret.vo = FactoryVisualOdometry.stereoDepth(1.5, 120, 2, 200, 50, false, disparity, tracker, bandType);

		return ret;
	}

	public static Info createDualTrackerPnP( Class bandType ) {
		Class derivType = GImageDerivativeOps.getDerivativeType(bandType);

		PkltConfig kltConfig = new PkltConfig();
		kltConfig.templateRadius = 3;
		kltConfig.pyramidScaling =  new int[]{1, 2, 4, 8};
		kltConfig.config.maxPerPixelError = 50;

		ConfigGeneralDetector configDetector = new ConfigGeneralDetector(600,3,1);

		PointTracker trackerLeft = FactoryPointTracker.klt(kltConfig, configDetector, bandType, derivType);
		PointTracker trackerRight = FactoryPointTracker.klt(kltConfig, configDetector, bandType, derivType);

		DescribeRegionPoint describe = FactoryDescribeRegionPoint.surfFast(null, bandType);

		Info ret = new Info();
		ret.name = "DualPnP";
		ret.imageType = ImageType.single(bandType);
		ret.vo = FactoryVisualOdometry.stereoDualTrackerPnP(110, 3, 1.5, 1.5, 200, 50,
				trackerLeft, trackerRight, describe, bandType);

		return ret;
	}

	public static Info createQuadPnP( Class bandType ) {
		Class derivType = GImageDerivativeOps.getDerivativeType(bandType);

		GeneralFeatureIntensity intensity =
				FactoryIntensityPoint.shiTomasi(1, false, derivType);
		NonMaxSuppression nonmax = FactoryFeatureExtractor.nonmax(new ConfigExtract(2, 50, 0, true, false, true));
		GeneralFeatureDetector general = new GeneralFeatureDetector(intensity,nonmax);
		general.setMaxFeatures(600);
		DetectorInterestPointMulti detector = new GeneralToInterestMulti(general,2,bandType,derivType);
		DescribeRegionPoint describe = FactoryDescribeRegionPoint.surfFast(null, bandType);
		DetectDescribeMulti detDescMulti =  new DetectDescribeMultiFusion(detector,null,describe);

		Info ret = new Info();
		ret.name = "QuadPnP";
		ret.imageType = ImageType.single(bandType);
		ret.vo = FactoryVisualOdometry.stereoQuadPnP(1.5, 0.5 ,75, Double.MAX_VALUE, 300, 50, detDescMulti, bandType);

		return ret;
	}

	public static class Info {
		public String name;
		public ImageType imageType;
		public StereoVisualOdometry vo;
	}

	public static void main(String[] args) throws IOException {

		StereoVisualOdometryRegression app = new StereoVisualOdometryRegression();

		app.setOutputDirectory(BoofRegressionConstants.CURRENT_DIRECTORY+"/"+ImageDataType.U8+"/");
		app.process(ImageDataType.U8);
	}

}
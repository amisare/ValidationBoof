package boofcv.metrics.disparity;

import boofcv.abst.feature.disparity.StereoDisparity;
import boofcv.factory.feature.disparity.*;
import boofcv.metrics.disparity.MiddleburyStereoEvaluation.Score;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayU8;
import org.ddogleg.struct.GrowQueue_F64;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static boofcv.metrics.disparity.MiddleburyStereoEvaluation.evaluateAll;
import static org.ddogleg.stats.UtilStatisticsQueue.mean;

/**
 * @author Peter Abeles
 */
public class EvaluateDisparity {
	public static final String PATH_MIDDLEBURY = "data/disparity/MiddEval3/trainingQ";
	public static final float BAD_THRESH = 2.0f; // If the disparity error is greater than this it is considered to be bad

	public PrintStream out = System.out;
	public PrintStream err = System.err;
	public PrintStream outRun = System.out;

	public void process() {
		List<TestSubject> subjects = createAlgorithms(120);

		out.println("# Middlebury Results for BAD_THRESH "+BAD_THRESH);
		out.println("# err = average, bp = bad percent, ip = invalid percent, tbp = total bad percent");
		out.println();
		for( TestSubject s : subjects ) {
			List<Score> results;
			try {
				results = evaluateAll(PATH_MIDDLEBURY, s.alg, BAD_THRESH);
			} catch( RuntimeException e ) {
				e.printStackTrace(err);
				continue;
			}
			GrowQueue_F64 allError = new GrowQueue_F64();
			GrowQueue_F64 allBad = new GrowQueue_F64();
			GrowQueue_F64 allInvalid = new GrowQueue_F64();
			GrowQueue_F64 allTotalBad = new GrowQueue_F64();
			double aveRuntime = 0;
			out.println(s.name);
			for( Score r : results ) {
				out.printf("%20s err=%5.2f bp=%5.1f ip=%5.1f tbp=%5.1f\n",r.name,r.aveError,r.badPercent,r.invalidPercent,r.totalBadPercent);
				allError.add(r.aveError);
				allBad.add(r.badPercent);
				allInvalid.add(r.invalidPercent);
				allTotalBad.add(r.totalBadPercent);
				aveRuntime += r.runtimeMS;
			}
			aveRuntime /= results.size();
			allError.sort();allBad.sort();allInvalid.sort();allTotalBad.sort();

			out.println();
			out.printf("Summary Mean : err=%5.2f bp=%5.1f ip=%5.1f tbp=%5.1f\n",
					mean(allError), mean(allBad), mean(allInvalid), mean(allTotalBad));
			out.printf("Summary 50%%  : err=%5.2f bp=%5.1f ip=%5.1f tbp=%5.1f\n",
					allError.getFraction(0.5),allBad.getFraction(0.5),allInvalid.getFraction(0.5),allTotalBad.getFraction(0.5));
			out.printf("Summary 95%%  : err=%5.2f bp=%5.1f ip=%5.1f tbp=%5.1f\n",
					allError.getFraction(0.95),allBad.getFraction(0.95),allInvalid.getFraction(0.95),allTotalBad.getFraction(0.95));
			out.println("----------------------------------------------------------------------------");

			outRun.printf("%-30s %.2f (ms)\n",s.name,aveRuntime);
		}
	}

	public List<TestSubject> createAlgorithms(int range ) {
		List<TestSubject> list = new ArrayList<>();

		list.add(create_BM5(range,3,DisparityError.SAD));
		list.add(create_BM5(range,3,DisparityError.CENSUS));
		list.add(create_BM5(range,3,DisparityError.NCC));

		list.add(create_BM(range,3,DisparityError.SAD));
		list.add(create_BM(range,3,DisparityError.CENSUS));
		list.add(create_BM(range,3,DisparityError.NCC));

		list.add(create_SGM(range,true,DisparitySgmError.ABSOLUTE_DIFFERENCE));
		list.add(create_SGM(range,true,DisparitySgmError.CENSUS));
		list.add(create_SGM(range,true,DisparitySgmError.MUTUAL_INFORMATION));

		return list;
	}

	private TestSubject create_BM5( int range , int region, DisparityError error ) {
		ConfigDisparityBMBest5 config = new ConfigDisparityBMBest5();
		config.regionRadiusX = config.regionRadiusY = region;
		config.errorType = error;
		config.disparityRange = range;

		TestSubject ts = new TestSubject();
		ts.alg = FactoryStereoDisparity.blockMatchBest5(config,GrayU8.class,GrayF32.class);
		ts.name = "BM5_"+error.name();
		return ts;
	}

	private TestSubject create_BM( int range , int region, DisparityError error ) {
		ConfigDisparityBM config = new ConfigDisparityBM();
		config.regionRadiusX = config.regionRadiusY = region;
		config.errorType = error;
		config.disparityRange = range;

		TestSubject ts = new TestSubject();
		ts.alg = FactoryStereoDisparity.blockMatch(config,GrayU8.class,GrayF32.class);
		ts.name = "BM_"+error.name();
		return ts;
	}

	private TestSubject create_SGM( int range , boolean block, DisparitySgmError error ) {
		ConfigDisparitySGM config = new ConfigDisparitySGM();
		config.paths = ConfigDisparitySGM.Paths.P16;
		config.useBlocks = block;
		config.errorType = error;
		config.disparityRange = range;

		TestSubject ts = new TestSubject();
		ts.alg = FactoryStereoDisparity.sgm(config,GrayU8.class,GrayF32.class);
		ts.name = "SGM_"+error.name();
		return ts;
	}

	public static class TestSubject {
		String name;
		StereoDisparity<GrayU8, GrayF32> alg;
	}

	public static void main(String[] args) {
		EvaluateDisparity app = new EvaluateDisparity();
		app.process();
	}
}
package boofcv.metrics.mvs;

import boofcv.abst.feature.associate.AssociateDescription;
import boofcv.abst.feature.associate.ScoreAssociation;
import boofcv.abst.feature.detdesc.DetectDescribePoint;
import boofcv.abst.feature.detect.interest.ConfigFastHessian;
import boofcv.abst.feature.disparity.StereoDisparity;
import boofcv.abst.geo.bundle.SceneStructureMetric;
import boofcv.alg.descriptor.UtilFeature;
import boofcv.alg.distort.ImageDistort;
import boofcv.alg.feature.associate.AssociateThreeByPairs;
import boofcv.alg.filter.derivative.LaplacianEdge;
import boofcv.alg.geo.PerspectiveOps;
import boofcv.alg.geo.RectifyImageOps;
import boofcv.alg.geo.bundle.cameras.BundlePinholeSimplified;
import boofcv.alg.geo.rectify.RectifyCalibrated;
import boofcv.alg.sfm.structure.ThreeViewEstimateMetricScene;
import boofcv.core.image.ConvertImage;
import boofcv.core.image.border.BorderType;
import boofcv.factory.feature.associate.FactoryAssociation;
import boofcv.factory.feature.detdesc.FactoryDetectDescribe;
import boofcv.factory.feature.disparity.DisparityAlgorithms;
import boofcv.factory.feature.disparity.FactoryStereoDisparity;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.io.image.UtilImageIO;
import boofcv.struct.calib.CameraPinholeRadial;
import boofcv.struct.feature.AssociatedTripleIndex;
import boofcv.struct.feature.BrightFeature;
import boofcv.struct.geo.AssociatedTriple;
import boofcv.struct.image.*;
import georegression.struct.point.Point2D_F64;
import georegression.struct.se.Se3_F64;
import org.ddogleg.struct.FastQueue;
import org.ejml.data.DMatrixRMaj;
import org.ejml.data.FMatrixRMaj;
import org.ejml.ops.ConvertMatrixData;

import java.awt.image.BufferedImage;

/**
 * Evaluates uncalibrated three view reconstruction using stereo disparity. Structure of each scene is unknown.
 * Observations indicate that stereo disparity is a resonable performance metric. if the sparse reconstruction
 * is poor the rectification will be poor too. Making stereo disparity calculation fail. A disparity is only accepted
 * if the region has sufficient texture and has a low enough error. Therefor only regions which can yield
 * results are considered and the matches must be good.
 *
 * @author Peter Abeles
 */
public class ThreeViewStereoPerformance {
    GrayU8 image01,image02,image03;
    double cx,cy;

    FastQueue<AssociatedTriple> associated = new FastQueue<>(AssociatedTriple.class,true);

    double score;
    // process time after loading the images and just after finishing
    long time0,time1;

    public boolean process( String path , String suffix ) {
        findTripplets(path,suffix);

        score = 0;
        int width = image01.width;
        int height = image01.height;

        // Run the reconstruction algorithm. This is the heart of what we are testing
        ThreeViewEstimateMetricScene alg = new ThreeViewEstimateMetricScene();

        if( !alg.process(associated.toList(),width,height) )
            return false;

        // Stereo computation. This is where we evaluate the performance
        computeDisparityScore(width, height, alg.getStructure());

        return true;
    }

    private void computeDisparityScore(int width, int height, SceneStructureMetric structure) {
        BundlePinholeSimplified cp = structure.getCameras()[0].getModel();
        CameraPinholeRadial intrinsic01 = new CameraPinholeRadial();
        intrinsic01.fsetK(cp.f,cp.f,0,cx,cy,width,height);
        intrinsic01.fsetRadial(cp.k1,cp.k2);

        cp = structure.getCameras()[1].getModel();
        CameraPinholeRadial intrinsic02 = new CameraPinholeRadial();
        intrinsic02.fsetK(cp.f,cp.f,0,cx,cy,width,height);
        intrinsic02.fsetRadial(cp.k1,cp.k2);

        Se3_F64 leftToRight = structure.views[1].worldToView;

        int max = 200;

        GrayF32 disparity = computeStereoDisparity(image01,image02,intrinsic01,intrinsic02,leftToRight,0,max);

        time1 = System.currentTimeMillis();

        // count number of pixels with a disparity more than zero and less than the max
        int total = 0;
        for (int i = 0; i < disparity.height; i++) {
            int idx = disparity.startIndex + i*disparity.stride;
            for (int j = 0; j < disparity.width; j++) {
                float v = disparity.data[idx++];
                if( v >= 1 && v <= max ) {
                    total++;
                }
            }
        }

        score = total/(double)(disparity.width*disparity.height);
    }

    private void findTripplets( String path , String suffix ) {
        BufferedImage buff01 = UtilImageIO.loadImage(path+"01."+suffix);
        BufferedImage buff02 = UtilImageIO.loadImage(path+"02."+suffix);
        BufferedImage buff03 = UtilImageIO.loadImage(path+"03."+suffix);

        Planar<GrayU8> color01 = ConvertBufferedImage.convertFrom(buff01,true, ImageType.pl(3,GrayU8.class));
        Planar<GrayU8> color02 = ConvertBufferedImage.convertFrom(buff02,true,ImageType.pl(3,GrayU8.class));
        Planar<GrayU8> color03 = ConvertBufferedImage.convertFrom(buff03,true,ImageType.pl(3,GrayU8.class));

        image01 = ConvertImage.average(color01,null);
        image02 = ConvertImage.average(color02,null);
        image03 = ConvertImage.average(color03,null);

        DetectDescribePoint<GrayU8, BrightFeature> detDesc = FactoryDetectDescribe.surfStable(
                new ConfigFastHessian(0, 4, 1000, 1, 9, 4, 2), null,null, GrayU8.class);

        FastQueue<Point2D_F64> locations01 = new FastQueue<>(Point2D_F64.class,true);
        FastQueue<Point2D_F64> locations02 = new FastQueue<>(Point2D_F64.class,true);
        FastQueue<Point2D_F64> locations03 = new FastQueue<>(Point2D_F64.class,true);

        FastQueue<BrightFeature> features01 = UtilFeature.createQueue(detDesc,100);
        FastQueue<BrightFeature> features02 = UtilFeature.createQueue(detDesc,100);
        FastQueue<BrightFeature> features03 = UtilFeature.createQueue(detDesc,100);

        time0 = System.currentTimeMillis();
        detDesc.detect(image01);

        int width = image01.width, height = image01.height;
        System.out.println("Image Shape "+width+" x "+height);
        cx = width/2;
        cy = height/2;
//		double scale = Math.max(cx,cy);

        // COMMENT ON center point zero
        for (int i = 0; i < detDesc.getNumberOfFeatures(); i++) {
            Point2D_F64 pixel = detDesc.getLocation(i);
            locations01.grow().set(pixel.x-cx,pixel.y-cy);
            features01.grow().setTo(detDesc.getDescription(i));
        }
        detDesc.detect(image02);
        for (int i = 0; i < detDesc.getNumberOfFeatures(); i++) {
            Point2D_F64 pixel = detDesc.getLocation(i);
            locations02.grow().set(pixel.x-cx,pixel.y-cy);
            features02.grow().setTo(detDesc.getDescription(i));
        }
        detDesc.detect(image03);
        for (int i = 0; i < detDesc.getNumberOfFeatures(); i++) {
            Point2D_F64 pixel = detDesc.getLocation(i);
            locations03.grow().set(pixel.x-cx,pixel.y-cy);
            features03.grow().setTo(detDesc.getDescription(i));
        }

        ScoreAssociation<BrightFeature> scorer = FactoryAssociation.scoreEuclidean(BrightFeature.class,true);
        AssociateDescription<BrightFeature> associate = FactoryAssociation.greedy(scorer, 0.1, true);

        AssociateThreeByPairs<BrightFeature> associateThree = new AssociateThreeByPairs<>(associate,BrightFeature.class);

        associateThree.setFeaturesA(features01);
        associateThree.setFeaturesB(features02);
        associateThree.setFeaturesC(features03);

        associateThree.associate();

        FastQueue<AssociatedTripleIndex> associatedIdx = associateThree.getMatches();
        associated.reset();
        for (int i = 0; i < associatedIdx.size; i++) {
            AssociatedTripleIndex p = associatedIdx.get(i);
            associated.grow().set(locations01.get(p.a),locations02.get(p.b),locations03.get(p.c));
        }
    }

    public GrayF32 computeStereoDisparity( GrayU8 distortedLeft, GrayU8 distortedRight ,
                                           CameraPinholeRadial intrinsicLeft ,
                                           CameraPinholeRadial intrinsicRight ,
                                           Se3_F64 leftToRight ,
                                           int minDisparity , int maxDisparity) {

//		drawInliers(origLeft, origRight, intrinsic, inliers);
        int width = distortedLeft.width;
        int height = distortedRight.height;

        // Rectify and remove lens distortion for stereo processing
        DMatrixRMaj rectifiedK = new DMatrixRMaj(3, 3);
        DMatrixRMaj rectifiedR = new DMatrixRMaj(3, 3);

        // rectify a colored image
        GrayU8 rectifiedLeft = distortedLeft.createSameShape();
        GrayU8 rectifiedRight = distortedRight.createSameShape();
        rectifyImages(distortedLeft, distortedRight, leftToRight, intrinsicLeft, intrinsicRight,
                rectifiedLeft, rectifiedRight, rectifiedK, rectifiedR);

        // compute disparity
        StereoDisparity<GrayS16, GrayF32> disparityAlg =
                FactoryStereoDisparity.regionSubpixelWta(DisparityAlgorithms.RECT_FIVE,
                        minDisparity, maxDisparity, 6, 6, 30, 3, 0.05, GrayS16.class);

        // Apply the Laplacian across the image to add extra resistance to changes in lighting or camera gain
        GrayS16 derivLeft = new GrayS16(width, height);
        GrayS16 derivRight = new GrayS16(width, height);
        LaplacianEdge.process(rectifiedLeft, derivLeft);
        LaplacianEdge.process(rectifiedRight, derivRight);

        // process and return the results
        disparityAlg.process(derivLeft, derivRight);
        return disparityAlg.getDisparity();
    }

    public static <T extends ImageBase<T>>
    void rectifyImages(T distortedLeft,
                       T distortedRight,
                       Se3_F64 leftToRight,
                       CameraPinholeRadial intrinsicLeft,
                       CameraPinholeRadial intrinsicRight,
                       T rectifiedLeft,
                       T rectifiedRight,
                       DMatrixRMaj rectifiedK,
                       DMatrixRMaj rectifiedR) {
        RectifyCalibrated rectifyAlg = RectifyImageOps.createCalibrated();

        // original camera calibration matrices
        DMatrixRMaj K1 = PerspectiveOps.pinholeToMatrix(intrinsicLeft, (DMatrixRMaj)null);
        DMatrixRMaj K2 = PerspectiveOps.pinholeToMatrix(intrinsicRight, (DMatrixRMaj)null);

        rectifyAlg.process(K1, new Se3_F64(), K2, leftToRight);

        // rectification matrix for each image
        DMatrixRMaj rect1 = rectifyAlg.getRect1();
        DMatrixRMaj rect2 = rectifyAlg.getRect2();
        rectifiedR.set(rectifyAlg.getRectifiedRotation());

        // New calibration matrix,
        rectifiedK.set(rectifyAlg.getCalibrationMatrix());

        // Adjust the rectification to make the view area more useful
        RectifyImageOps.fullViewLeft(intrinsicLeft, rect1, rect2, rectifiedK);

        // undistorted and rectify images
        FMatrixRMaj rect1_F32 = new FMatrixRMaj(3,3);
        FMatrixRMaj rect2_F32 = new FMatrixRMaj(3,3);
        ConvertMatrixData.convert(rect1, rect1_F32);
        ConvertMatrixData.convert(rect2, rect2_F32);

        ImageDistort<T,T> distortLeft =
                RectifyImageOps.rectifyImage(intrinsicLeft, rect1_F32, BorderType.SKIP, distortedLeft.getImageType());
        ImageDistort<T,T> distortRight =
                RectifyImageOps.rectifyImage(intrinsicRight, rect2_F32, BorderType.SKIP, distortedRight.getImageType());

        distortLeft.apply(distortedLeft, rectifiedLeft);
        distortRight.apply(distortedRight, rectifiedRight);
    }

    public double getScore() {
        return score;
    }

    public long getElapsedTime() {
        return time1-time0;
    }
}
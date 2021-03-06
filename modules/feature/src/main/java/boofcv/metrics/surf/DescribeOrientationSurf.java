/*
 * Copyright (c) 2011-2012, Peter Abeles. All Rights Reserved.
 *
 * This file is part of the SURF Performance Benchmark
 * (https://github.com/lessthanoptimal/SURFPerformance).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.metrics.surf;

import boofcv.abst.feature.describe.DescribeRegionPoint;
import boofcv.abst.feature.orientation.OrientationIntegral;
import boofcv.alg.feature.describe.DescribePointSurf;
import boofcv.alg.transform.ii.GIntegralImageOps;
import boofcv.struct.BoofDefaults;
import boofcv.struct.feature.BrightFeature;
import boofcv.struct.image.ImageGray;
import boofcv.struct.image.ImageType;

/**
 * Adds orientation estimation to SURF description calculation.
 *
 * @author Peter Abeles
 */
public class DescribeOrientationSurf<T extends ImageGray<T>, II extends ImageGray<II>>
		implements DescribeRegionPoint<T,BrightFeature>
{
	private OrientationIntegral<II> orientation;
	private DescribePointSurf<II> describe;

	// storage for integral image
	private II ii;

	ImageType<T> imageType;

	public DescribeOrientationSurf(OrientationIntegral<II> orientation,
								   DescribePointSurf<II> describe) {
		this.orientation = orientation;
		this.describe = describe;
//		this.imageType = ImageDataType.single(imageType);
	}

	@Override
	public void setImage(T image) {
		if( ii != null ) {
			ii.reshape(image.width,image.height);
		}

		// compute integral image
		ii = GIntegralImageOps.transform(image, ii);
		orientation.setImage(ii);
		describe.setImage(ii);
	}

	@Override
	public BrightFeature createDescription() {
		return describe.createDescription();
	}

	@Override
	public boolean process(double x, double y, double angle, double radius, BrightFeature ret) {

		double scale = radius/ BoofDefaults.SURF_SCALE_TO_RADIUS;
		orientation.setObjectRadius(radius);
		angle = orientation.compute(x,y);
		describe.describe(x,y, angle, scale, ret);

		return true;
	}

	@Override
	public boolean requiresRadius() {
		return false;
	}

	@Override
	public boolean requiresOrientation() {
		return true;
	}

	@Override
	public ImageType<T> getImageType() {
		return imageType;
	}

	@Override
	public double getCanonicalWidth() {
		return describe.getCanonicalWidth();
	}

	@Override
	public Class<BrightFeature> getDescriptionType() {
		return BrightFeature.class;
	}
}

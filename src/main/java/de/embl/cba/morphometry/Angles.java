package de.embl.cba.morphometry;

import net.imglib2.Point;
import net.imglib2.util.LinAlgHelpers;

import static de.embl.cba.morphometry.Constants.X;
import static de.embl.cba.morphometry.Constants.Y;
import static java.lang.Math.acos;
import static java.lang.Math.atan;
import static java.lang.Math.toDegrees;

public abstract class Angles
{
	public static double angleToZAxisInDegrees( Point maximum )
	{
		double angleToZAxisInDegrees;

		if ( maximum.getIntPosition( Y ) == 0 )
		{
			angleToZAxisInDegrees = Math.signum( maximum.getDoublePosition( X ) ) * 90;
		}
		else
		{
			angleToZAxisInDegrees = toDegrees( atan( maximum.getDoublePosition( X ) / maximum.getDoublePosition( Y ) ) );

			if ( maximum.getDoublePosition( Y ) < 0 )
			{
				angleToZAxisInDegrees += 180;
			}
		}

		return angleToZAxisInDegrees;
	}


	public static double angleOfSpindleAxisToXaxisInRadians( final double[] vector )
	{
		double[] xAxis = new double[]{  1,0, 0};

		final double dot = LinAlgHelpers.dot( xAxis, vector );

		double angleInRadians = -1.0 * Math.signum( vector[ 1 ] ) * acos( dot / ( LinAlgHelpers.length( xAxis ) * LinAlgHelpers.length( vector ) ) );


		return angleInRadians;
	}
}

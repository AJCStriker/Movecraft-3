/*
 * This file is part of Movecraft.
 *
 * Movecraft is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * Movecraft is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Movecraft. If not, see <http://www.gnu.org/licenses/>.
 */

package net.countercraft.movecraft.utils;

import org.bukkit.Location;

public class MathUtils {

	public static boolean playerIsWithinBoundingPolygon(int[][][] box, int minX, int minZ, MovecraftLocation l) {

		if ((l.getX() >= minX) && (l.getX() < (minX + box.length))) {
			// PLayer is within correct X boundary
			if ((l.getZ() >= minZ) && (l.getZ() < (minZ + box[l.getX() - minX].length))) {
				// Player is within valid Z boundary
				int minY, maxY;

				try {
					minY = box[l.getX() - minX][l.getZ() - minZ][0];
					maxY = box[l.getX() - minX][l.getZ() - minZ][1];
				} catch (NullPointerException e) {
					return false;
				}

				if ((l.getY() >= minY) && (l.getY() <= (maxY + 2))) {
					// Player is on board the vessel
					return true;
				}

			}

		}

		return false;
	}

	public static MovecraftLocation bukkit2MovecraftLoc(Location l) {
		return new MovecraftLocation(l.getBlockX(), l.getBlockY(), l.getBlockZ());
	}

	public static MovecraftLocation rotateVec(Rotation r, MovecraftLocation l) {
		MovecraftLocation newLocation = new MovecraftLocation(0, l.getY(), 0);

		int cos = 0; // SINE OF 90 OR -90 DEGREES
		int sin = (r == Rotation.CLOCKWISE) ? 1 : -1; // SINE OF 90 DEGREES OR
														// -90 DEGREES

		int x = ((l.getX() * cos) + (l.getZ() * -sin));
		int z = ((l.getX() * sin) + (l.getZ() * cos));

		newLocation.setX(x);
		newLocation.setZ(z);

		return newLocation;
	}

	public static double[] rotateVec(Rotation r, double x, double z) {
		int cos = 0;
		int sin = (r == Rotation.CLOCKWISE) ? 1 : -1;

		double newX = ((x * cos) + (z * -sin));
		double newZ = ((x * sin) + (z * cos));

		return new double[] { newX, newZ };
	}

	public static double[] rotateVecNoRound(Rotation r, double x, double z) {
		int cos = 0;
		int sin = (r == Rotation.CLOCKWISE) ? 1 : -1;

		double newX = (x * cos) + (z * -sin);
		double newZ = (x * sin) + (z * cos);

		return new double[] { newX, newZ };
	}

	public static int positiveMod(int mod, int divisor) {
		if (mod < 0) {
			mod += divisor;
		}

		return mod;
	}
}

/*
 * This file is part of Movecraft.
 *
 *     Movecraft is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Movecraft is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Movecraft.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.countercraft.movecraft.async;

import net.countercraft.movecraft.Movecraft;
import net.countercraft.movecraft.async.detection.DetectionTask;
import net.countercraft.movecraft.async.detection.DetectionTaskData;
import net.countercraft.movecraft.async.rotation.RotationTask;
import net.countercraft.movecraft.async.translation.TranslationTask;
import net.countercraft.movecraft.config.Settings;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.craft.CraftManager;
import net.countercraft.movecraft.localisation.I18nSupport;
import net.countercraft.movecraft.utils.BlockUtils;
import net.countercraft.movecraft.utils.EntityUpdateCommand;
import net.countercraft.movecraft.utils.MapUpdateCommand;
import net.countercraft.movecraft.utils.MapUpdateManager;
import net.countercraft.movecraft.utils.MathUtils;
import net.countercraft.movecraft.utils.MovecraftLocation;

import org.apache.commons.collections.ListUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;

public class AsyncManager extends BukkitRunnable {
	private static final AsyncManager instance = new AsyncManager();
	private final HashMap<AsyncTask, Craft> ownershipMap = new HashMap<AsyncTask, Craft>();
	private final BlockingQueue<AsyncTask> finishedAlgorithms = new LinkedBlockingQueue<AsyncTask>();
	private final HashSet<Craft> clearanceSet = new HashSet<Craft>();
	private final HashMap<World, ArrayList<MovecraftLocation>> sinkingBlocks = new HashMap<World, ArrayList<MovecraftLocation>>();
	private final HashMap<World, HashSet<MovecraftLocation>> waterFillBlocks = new HashMap<World, HashSet<MovecraftLocation>>();
	private long lastSinkingUpdate = 0;

	public static AsyncManager getInstance() {
		return instance;
	}

	private AsyncManager() {
	}

	public void submitTask( AsyncTask task, Craft c ) {
		if ( c.isNotProcessing() ) {
			c.setProcessing( true );
			ownershipMap.put( task, c );
			task.runTaskAsynchronously( Movecraft.getInstance() );
		}
	}

	public void submitCompletedTask( AsyncTask task ) {
		finishedAlgorithms.add( task );
	}

	void processAlgorithmQueue() {
		int runLength = 10;
		int queueLength = finishedAlgorithms.size();

		runLength = Math.min( runLength, queueLength );

		for ( int i = 0; i < runLength; i++ ) {
			boolean sentMapUpdate=false;
			AsyncTask poll = finishedAlgorithms.poll();
			Craft c = ownershipMap.get( poll );

			if ( poll instanceof DetectionTask ) {
				// Process detection task

				DetectionTask task = ( DetectionTask ) poll;
				DetectionTaskData data = task.getData();

				Player p = data.getPlayer();
				Craft pCraft = CraftManager.getInstance().getCraftByPlayer( p );

				if ( pCraft != null && p != null ) {
					//Player is already controlling a craft
					p.sendMessage( String.format( I18nSupport.getInternationalisedString( "Detection - Failed - Already commanding a craft" ) ) );
				} else {
					if ( data.failed() ) {
						if(p!=null)
							p.sendMessage( data.getFailMessage() );
					} else {
						Craft[] craftsInWorld = CraftManager.getInstance().getCraftsInWorld( c.getW() );
						boolean failed = false;

						if ( craftsInWorld != null ) {
							for ( Craft craft : craftsInWorld ) {

								if ( BlockUtils.arrayContainsOverlap( craft.getBlockList(), data.getBlockList() ) && c.getType().getCruiseOnPilot()==false ) {
									p.sendMessage( String.format( I18nSupport.getInternationalisedString( "Detection - Failed Craft is already being controlled" ) ) );
									failed = true;
								}

							}
						}
						if ( !failed ) {
							c.setBlockList( data.getBlockList() );
							c.setHitBox( data.getHitBox() );
							c.setMinX( data.getMinX() );
							c.setMinZ( data.getMinZ() );
							if(p!=null) {
								p.sendMessage( String.format( I18nSupport.getInternationalisedString( "Detection - Successfully piloted craft" ) ) );
								Movecraft.getInstance().getLogger().log( Level.INFO, String.format( I18nSupport.getInternationalisedString( "Detection - Success - Log Output" ), p.getName(), c.getType().getCraftName(), c.getBlockList().length, c.getMinX(), c.getMinZ() ) );
							} else {
								Movecraft.getInstance().getLogger().log( Level.INFO, String.format( I18nSupport.getInternationalisedString( "Detection - Success - Log Output" ), "NULL PLAYER", c.getType().getCraftName(), c.getBlockList().length, c.getMinX(), c.getMinZ() ) );								
							}
							CraftManager.getInstance().addCraft( c, p );
						}
					}
				}


			} else if ( poll instanceof TranslationTask ) {
				//Process translation task

				TranslationTask task = ( TranslationTask ) poll;
				Player p = CraftManager.getInstance().getPlayerFromCraft( c );

				// Check that the craft hasn't been sneakily unpiloted
		//		if ( p != null ) {     cruiseOnPilot crafts don't have player pilots

					if ( task.getData().failed() ) {
						//The craft translation failed
						if( p != null )
							p.sendMessage( task.getData().getFailMessage() );
						if(task.getData().collisionExplosion()) {
							MapUpdateCommand[] updates = task.getData().getUpdates();
							c.setBlockList( task.getData().getBlockList() );
							boolean failed = MapUpdateManager.getInstance().addWorldUpdate( c.getW(), updates, null);

							if ( failed ) {
								Movecraft.getInstance().getLogger().log( Level.SEVERE, String.format( I18nSupport.getInternationalisedString( "Translation - Craft collision" ) ) );
							} else {
								sentMapUpdate=true;
							}
						}
					} else {
						//The craft is clear to move, perform the block updates

						MapUpdateCommand[] updates = task.getData().getUpdates();
						EntityUpdateCommand[] eUpdates=task.getData().getEntityUpdates();
						boolean failed = MapUpdateManager.getInstance().addWorldUpdate( c.getW(), updates, eUpdates);

						if ( !failed ) {
							sentMapUpdate=true;
							c.setBlockList( task.getData().getBlockList() );


							c.setMinX( task.getData().getMinX() );
							c.setMinZ( task.getData().getMinZ() );
							c.setHitBox( task.getData().getHitbox() );


						} else {

							Movecraft.getInstance().getLogger().log( Level.SEVERE, String.format( I18nSupport.getInternationalisedString( "Translation - Craft collision" ) ) );

						}
					}

	//			}


			} else if ( poll instanceof RotationTask ) {
				// Process rotation task
				RotationTask task = ( RotationTask ) poll;
				Player p = CraftManager.getInstance().getPlayerFromCraft( c );

				// Check that the craft hasn't been sneakily unpiloted
				if ( p != null ) {

					if ( task.isFailed() ) {
						//The craft translation failed
						p.sendMessage( task.getFailMessage() );
					} else {
						MapUpdateCommand[] updates = task.getUpdates();
						EntityUpdateCommand[] eUpdates=task.getEntityUpdates();

						boolean failed = MapUpdateManager.getInstance().addWorldUpdate( c.getW(), updates, eUpdates);
 
						if ( !failed ) {
							sentMapUpdate=true;
							
							c.setBlockList( task.getBlockList() );
							c.setMinX( task.getMinX() );
							c.setMinZ( task.getMinZ() );
							c.setHitBox( task.getHitbox() );

						} else {

							Movecraft.getInstance().getLogger().log( Level.SEVERE, String.format( I18nSupport.getInternationalisedString( "Rotation - Craft Collision" ) ) );

						}
					}
				}
			}

			ownershipMap.remove( poll );
			
			// only mark the craft as having finished updating if you didn't send any updates to the map updater. Otherwise the map updater will mark the crafts once it is done with them.
			if(!sentMapUpdate) {
				clear( c ); 
			}
		}
	}
	
	public void processCruise() {
		for( World w : Bukkit.getWorlds()) {
			if(w!=null && CraftManager.getInstance().getCraftsInWorld(w)!=null) {
				for (Craft pcraft : CraftManager.getInstance().getCraftsInWorld(w)) {
					if(pcraft!=null) {
						if(pcraft.getCruising()) {
							long ticksElapsed = ( System.currentTimeMillis() - pcraft.getLastCruiseUpdate() ) / 50;
							if ( Math.abs( ticksElapsed ) >= pcraft.getType().getTickCooldown() ) {
								int dx=0;
								int dz=0;
								// ship faces west
								if(pcraft.getCruiseDirection()==0x5) {
									dx=0-1-pcraft.getType().getCruiseSkipBlocks();
								}
								// ship faces east
								if(pcraft.getCruiseDirection()==0x4) {
									dx=1+pcraft.getType().getCruiseSkipBlocks();
								}
								// ship faces north
								if(pcraft.getCruiseDirection()==0x2) {
									dz=1+pcraft.getType().getCruiseSkipBlocks();
								}
								// ship faces south
								if(pcraft.getCruiseDirection()==0x3) {
									dz=0-1-pcraft.getType().getCruiseSkipBlocks();
								}
								pcraft.translate(dx, 0, dz);
								pcraft.setLastCruisUpdate(System.currentTimeMillis());
							}
							
						} else {
//							if(pcraft.getLastDX()!=0 || pcraft.getLastDY()!=0 || pcraft.getLastDZ()!=0) {
							if(pcraft.getKeepMoving()) {
								long rcticksElapsed = ( System.currentTimeMillis() - pcraft.getLastRightClick() ) / 50;
								rcticksElapsed=Math.abs(rcticksElapsed);
								// if they are holding the button down, keep moving
								if (rcticksElapsed <= 10 ) {
									long ticksElapsed = ( System.currentTimeMillis() - pcraft.getLastCruiseUpdate() ) / 50;
									if ( Math.abs( ticksElapsed ) >= pcraft.getType().getTickCooldown() ) {
										pcraft.translate(pcraft.getLastDX(), pcraft.getLastDY(), pcraft.getLastDZ());
										pcraft.setLastCruisUpdate(System.currentTimeMillis());
									}
								}
							}
							if(pcraft.getPilotLocked()==true && pcraft.isNotProcessing()) {
								
								Player p = CraftManager.getInstance().getPlayerFromCraft( pcraft );
								if ( MathUtils.playerIsWithinBoundingPolygon( pcraft.getHitBox(), pcraft.getMinX(), pcraft.getMinZ(), MathUtils.bukkit2MovecraftLoc( p.getLocation() ) ) ) {
									double movedX=p.getLocation().getX()-pcraft.getPilotLockedX();
									double movedZ=p.getLocation().getZ()-pcraft.getPilotLockedZ();
									int dX=0;
									int dZ=0;
									if(movedX>0.15)
										dX=1;
									else if(movedX<-0.15)
										dX=-1;
									if(movedZ>0.15)
										dZ=1;
									else if(movedZ<-0.15)
										dZ=-1;
									if(dX!=0 || dZ!=0) {
										long timeSinceLastMoveCommand=System.currentTimeMillis()-pcraft.getLastRightClick();
/*										boolean flippedDirection=false;
										if((dX<0 && pcraft.getLastDX()>0)||(dX>0 && pcraft.getLastDX()<0))
											flippedDirection=true;
										if((dZ<0 && pcraft.getLastDZ()>0)||(dZ>0 && pcraft.getLastDZ()<0))
											flippedDirection=true;
										if((!flippedDirection) || timeSinceLastMoveCommand>500) {*/
										// wait before accepting new move commands to help with bouncing. Also ignore extreme movement
										if(Math.abs(movedX)<0.2 && Math.abs(movedZ)<0.2 && timeSinceLastMoveCommand>300) { 

											pcraft.setLastRightClick(System.currentTimeMillis());
											long ticksElapsed = ( System.currentTimeMillis() - pcraft.getLastCruiseUpdate() ) / 50;
											if ( Math.abs( ticksElapsed ) >= pcraft.getType().getTickCooldown() ) {
												pcraft.translate(dX, 0, dZ);
												pcraft.setLastCruisUpdate(System.currentTimeMillis());
											}
//											if(timeSinceLastMoveCommand<750) {
												pcraft.setLastDX(dX);
												pcraft.setLastDY(0);
												pcraft.setLastDZ(dZ);
												pcraft.setKeepMoving(true);
/*											} else {
												pcraft.setLastDX(dX);
												pcraft.setLastDY(0);
												pcraft.setLastDZ(dZ);
												pcraft.setKeepMoving(false);
											}*/
										} else {
											Location loc=p.getLocation();
											loc.setX(pcraft.getPilotLockedX());
											loc.setY(pcraft.getPilotLockedY());
											loc.setZ(pcraft.getPilotLockedZ());
											Vector pVel=new Vector(0.0,0.0,0.0);
											p.teleport(loc);
											p.setVelocity(pVel);
										}
									} 
								}
							}
						}
					}
				}
			}
		}

	}
	
	public void processSinking() {

		for( World w : Bukkit.getWorlds()) {
			if(w!=null && CraftManager.getInstance().getCraftsInWorld(w)!=null) {

				// check every few seconds for every craft to see if it should be sinking
				for (Craft pcraft : CraftManager.getInstance().getCraftsInWorld(w)) {
					if(pcraft!=null) {
						if( pcraft.getType().getSinkPercent()!=0.0 && pcraft.isNotProcessing()) {
							long ticksElapsed = ( System.currentTimeMillis() - pcraft.getLastBlockCheck() ) / 50;
						
							if(ticksElapsed>Settings.SinkCheckTicks) {
								int totalBlocks=0;
								HashMap<ArrayList<Integer>, Integer> foundFlyBlocks = new HashMap<ArrayList<Integer>, Integer>();

								// go through each block in the blocklist, and if its in the FlyBlocks, total up the number of them
								for(MovecraftLocation l : pcraft.getBlockList()) {
									Integer blockID=w.getBlockAt(l.getX(), l.getY(), l.getZ()).getTypeId();
									Integer dataID=(int)w.getBlockAt(l.getX(), l.getY(), l.getZ()).getData();
									Integer shiftedID=(blockID<<4)+dataID+10000;
									for(ArrayList<Integer> flyBlockDef : pcraft.getType().getFlyBlocks().keySet()) {
										if(flyBlockDef.contains(blockID) || flyBlockDef.contains(shiftedID)) {
											Integer count=foundFlyBlocks.get(flyBlockDef);
											if(count==null) {
												foundFlyBlocks.put(flyBlockDef, 1);
											} else {
												foundFlyBlocks.put(flyBlockDef, count+1);
											}
										}
									}
									
									if(blockID!=0) {  
										totalBlocks++;
									}
								}
								
								// now see if any of the resulting percentages are below the threshold specified in SinkPercent
								boolean isSinking=false;
								for(ArrayList<Integer> i : pcraft.getType().getFlyBlocks().keySet()) {
									int numfound=0;
									if(foundFlyBlocks.get(i)!=null) {
										numfound=foundFlyBlocks.get(i);
									}
									double percent=((double)numfound/(double)totalBlocks)*100.0;
									double flyPercent=pcraft.getType().getFlyBlocks().get(i).get(0);
									double sinkPercent=flyPercent*pcraft.getType().getSinkPercent()/100.0;
									if(percent<sinkPercent) {
										isSinking=true;
									}
									
								}
								if(totalBlocks==0) {
									isSinking=true;
								}
								
								// if the craft is sinking, let the player know and release the craft. Otherwise update the time for the next check
								if(isSinking) {
									// add the blocks of the craft to the sinking blocks
									if(sinkingBlocks.get(w)==null) {
										ArrayList<MovecraftLocation> t=new ArrayList<MovecraftLocation>();
										sinkingBlocks.put(w, t);
									}
									sinkingBlocks.get(w).addAll(Arrays.asList(pcraft.getBlockList()));
									Player p = CraftManager.getInstance().getPlayerFromCraft( pcraft );
									if(p!=null)
										p.sendMessage( String.format( I18nSupport.getInternationalisedString( "Player- Craft is sinking" ) ) );
									CraftManager.getInstance().removeCraft( pcraft );
								} else {
									pcraft.setLastBlockCheck(System.currentTimeMillis());
								}
							}
						}
					}
				}
			}
		}
		
		// sink all the sinkingBlocks every second
		final int[] fallThroughBlocks = new int[]{ 0, 8, 9, 10, 11, 31, 37, 38, 39, 40, 50, 51, 55, 59, 65, 69, 70, 72, 75, 76, 77, 78, 83, 93, 94, 111, 141, 142, 143, 171};  
		for( World w : Bukkit.getWorlds()) {
			if(w!=null) {
				long ticksElapsed = ( System.currentTimeMillis() - lastSinkingUpdate ) / 50;
				if(ticksElapsed>=Settings.SinkRateTicks && sinkingBlocks.get( w )!=null) {
					Iterator<MovecraftLocation> sBlocks=sinkingBlocks.get( w ).iterator();
					HashSet<MovecraftLocation> oldBlockSet = new HashSet<MovecraftLocation>();
					for(MovecraftLocation l : sinkingBlocks.get( w )) {
						MovecraftLocation n=new MovecraftLocation(l.getX(),l.getY(),l.getZ());
						oldBlockSet.add(n);
					}
					
					ArrayList<MapUpdateCommand> updates=new ArrayList<MapUpdateCommand>();
					while(sBlocks.hasNext()) {
						MovecraftLocation l=sBlocks.next();
						MovecraftLocation oldLoc=new MovecraftLocation(l.getX(), l.getY(), l.getZ());
						MovecraftLocation newLoc=new MovecraftLocation(l.getX(), l.getY()-1, l.getZ());
						if (l.getY()>0){
							Block oldBlock=w.getBlockAt(l.getX(), l.getY(), l.getZ());
							Block newBlock=w.getBlockAt(l.getX(), l.getY()-1, l.getZ());
							// If the source is air, remove it from processing
							if(oldBlock.getTypeId()!=0) {
								
								// if falling through another falling block, search through all blocks downward to see if they are all on something solid. If so, don't fall
								MovecraftLocation testLoc=new MovecraftLocation(newLoc.getX(),newLoc.getY(),newLoc.getZ());
								while(oldBlockSet.contains(testLoc)) {
									testLoc.setY(testLoc.getY()-1);
								}
								
								Block testBlock=w.getBlockAt(testLoc.getX(), testLoc.getY(), testLoc.getZ());
								if(Arrays.binarySearch(fallThroughBlocks,testBlock.getTypeId())>=0) {
									// remember which blocks used to be water, so we can fill it back in later
									if(newBlock.getTypeId()==9) {
										if(waterFillBlocks.get( w )==null) {
											HashSet<MovecraftLocation> newA=new HashSet<MovecraftLocation>();
											waterFillBlocks.put( w ,newA);
										}
										waterFillBlocks.get(w).add(newLoc);
									}
									int newTypeId=oldBlock.getTypeId();
									if(newTypeId==9) {
										newTypeId=0;
									}
									MapUpdateCommand c=new MapUpdateCommand(oldLoc, newLoc, newTypeId, null);
									updates.add(c);
									l.setY(l.getY()-1);
									} else {

									// if the block below is solid, remove the block from the falling list and the waterfill list. Also remove it from oldblocks, so it doesn't get filled in with air/water
									waterFillBlocks.remove(l);
									oldBlockSet.remove(l);
									sBlocks.remove();

									}
								
							} else {
								// don't process air, waste of time
//								oldBlockSet.remove(l);
								sBlocks.remove();							
							}
						} else {
							// don't process under 0 level
							 sBlocks.remove();
						}
					}
					
					//now add in air or water to where the blocks used to be
//					List<MovecraftLocation> fillLocation = ListUtils.subtract( Arrays.asList( oldBlockSet ), Arrays.asList( sinkingBlocks.get(w) ) );
					for(MovecraftLocation l : oldBlockSet) {
						if(!sinkingBlocks.get(w).contains(l)) {
							MapUpdateCommand c;
							if(waterFillBlocks.get( w )!=null) {
								//don't fill in water if its above sea level - necessary to avoid leaving water hanging in the air
								if(l.getY()<=w.getSeaLevel() && waterFillBlocks.get( w ).contains(l)) {
									c=new MapUpdateCommand(l,9,null);
								} else {
									c=new MapUpdateCommand(l,0,null);
								}
							} else {
								c=new MapUpdateCommand(l,0,null);							
							}
						updates.add(c);
						}
					}
					
					boolean failed = MapUpdateManager.getInstance().addWorldUpdate( w, updates.toArray(new MapUpdateCommand [0]), null);

					lastSinkingUpdate=System.currentTimeMillis();
				}
			}
		}
	}

	public void run() {
		clearAll();
		processCruise();
		processSinking();
		processAlgorithmQueue();
	}

	private void clear( Craft c ) {
		clearanceSet.add( c );
	}

	private void clearAll() {
		for ( Craft c : clearanceSet ) {
			c.setProcessing( false );
		}

		clearanceSet.clear();
	}
}

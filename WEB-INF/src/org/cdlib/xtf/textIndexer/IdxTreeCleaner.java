package org.cdlib.xtf.textIndexer;

/**
 * Copyright (c) 2004, Regents of the University of California
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, 
 *   this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, 
 *   this list of conditions and the following disclaimer in the documentation 
 *   and/or other materials provided with the distribution.
 * - Neither the name of the University of California nor the names of its
 *   contributors may be used to endorse or promote products derived from this 
 *   software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.File;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;

import org.cdlib.xtf.util.Path;
import org.cdlib.xtf.util.Trace;


////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////

/**
 * This class purges "incomplete" documents from a Lucene index. <br><br>
 * 
 * A "complete" document consists of all the overlapping text chunks for the 
 * document plus a special docInfo chunk that provides summary information 
 * about the rest of the chunks in the document. Since the summary chunk is 
 * the last chunk written for a document, any early termination of the indexer 
 * (due to errors, or user abort) will leave text chunks in the database 
 * without the summary chunk, which is called an "incomplete" document. <br><br>
 * 
 * Since the search engine relies on the summary chunk to correctly search
 * overlapping text chunks, the absence of the summary chunk will cause 
 * problems. Consequently, this class is used to purge text chunks from the 
 * index that do not have a corresponding summary chunk. <br><br>  
 *
 * To use this class, simply instantiate a copy, and call the 
 * {@link IdxTreeCleaner#processDir(File) processDir()}
 * method on a directory containing an index. Note that the directory passed
 * may also be a root directory with many index sub-directories if desired.
 * 
 */

public class IdxTreeCleaner 

{

  
  ////////////////////////////////////////////////////////////////////////////

  /**
   * Create an <code>IdxTreeCleaner</code> instance and call this method to 
   * remove "incomplete" documents from an index directory or a root 
   * directory containing multiple indices. 
   * <br><br>
   *                     
   * @param  dir         The index database directory clean. May be a directory 
   *                     containing a single index, or the root directory of a 
   *                     tree containing multiple indices. 
   *                     <br><br>
   * 
   * @throws Exception   Passes back any exceptions generated by the
   *                     cleanIndex() function, which is called for 
   *                     each index sub-directory found.
   *                     <br><br>
   * 
   * @.notes             This method also calls itself recursively to process
   *                     potential index sub-directories below the passed
   *                     directory. <br><br>
   * 
   *     For an explanation of "complete" and "incomplete" documents, see the 
   *     <code>IdxTreeCleaner<code> class description. 
   */
  
  public void processDir( File dir ) throws Exception
  
  {
    
    // If the file we were passed was in fact a directory...
    if( dir.isDirectory() ) {
      
      // And it contains an index, see if it needs any culling.
      if( IndexReader.indexExists( dir ) )
          cleanIndex( dir );
      
      else {
          // Get the list of files it contains.
          String[] files = dir.list();
  
          // And process each of them.
          for( int i = 0; i < files.length; i++ )
            processDir( new File(dir, files[i]) );
      }
      
      return;
    
    } // if( dir.isDirectory() )
    
    // The current file is not a directory, so skip it.
    
  } // processDir()
  
  
  ////////////////////////////////////////////////////////////////////////////

  /**
   * Performs the actual work of removing incomplete documents from an index. 
   * <br><br>
   *                     
   * @param  idxDirToClean  The index database directory clean. This directory
   *                        must contain a single Lucene index.
   *                        <br><br>
   * 
   * @throws Exception      Passes back any exceptions generated by Lucene 
   *                        during the opening of, reading of, or writing to 
   *                        the specified index. <br><br>
   * 
   *     For an explanation of "complete" and "incomplete" documents, see the 
   *     <code>IdxTreeCleaner</code> class description. 
   */
  public void cleanIndex( File idxDirToClean ) throws Exception
  
  {
    IndexReader indexReader;
    
    // Tell what index we're working on...
    Trace.info( "Index: [" + 
                Path.normalizePath( idxDirToClean.toString() ) +
                "] " );
    
    // Try to open the index for reading. If we fail and 
    // throw, skip the index.
    //
    try {
        indexReader = IndexReader.open( idxDirToClean );
    }
    catch( Exception e ) {
      
        Trace.warning( "*** Warning: Unable to Open Index [" + 
                       idxDirToClean + "] for Cleaning." );
        return;
    }
    
    // Determine the number of chunks in the index, and which one is last.
    int chunkCount = indexReader.numDocs();
    int lastChunk  = chunkCount-1;
    
    // Start with no incomplete documents cleaned.
    int cleanCount = 0;
    
    // The last chunk in an index must be a docInfo chunk. If it is not, the
    // chunk is a partial write of an incomplete document and must be removed.
    //
    // In the case where the last chunk is marked as 'deleted', it could be
    // because either (1) the last completed document was deleted, or (2) the
    // last cleanIndex() pass didn't finish. We keep on going to make sure we
    // complete in the case of (2).
    //
    while( lastChunk > 0 ) {
      
          // If deleted, keep going until we reach a non-deleted chunk.
        if( indexReader.isDeleted(lastChunk) ) {
              lastChunk--;
              continue;
        }
      
        // Get the last chunk in the index.
        Document chunk = indexReader.document( lastChunk );
        
        // If this chunk is a docInfo chunk, the index ends in a complete 
        // document, and we're done.
        //
        if( chunk.get("docInfo") != null ) break;
        
        // Otherwise, it is a chunk from an incomplete document, so delete it.  
        try{
            indexReader.delete( lastChunk );
        }
        catch( Exception e ) {
            
            // Log the problem.
            Trace.tab();
            Trace.error( "*** Exception Purging Incomplete Document: " +
                         e.getMessage() );
            Trace.untab();
           
            // Close the index.
            indexReader.close();
            
            // And pass the exception up the call chain.
            throw e;
        }
        
         cleanCount++;
         lastChunk--;        
    }
    
    // Close up the index reader.
    indexReader.close();
  
    // Now if the number of chunks encounted equals the number
    // of chunks cleaned, we can delete the whole index directory.
    //
    if( chunkCount == cleanCount ) {
      
        int deleteFailCount = 0;
      
        // FIrst, we need to delete all the files in the index 
        // directory, before we can delete the directory itself.
        //
        File[] fileList = idxDirToClean.listFiles();
      
        // Delete the files.
        for( int j = 0; j < fileList.length; j++ ) {
          
            // Try to delete the current file.
            try { fileList[j].delete(); } 
          
            // If we could not, display a warning and track the delete
            // failure count.
            //
            catch( Exception e ) {
                Trace.tab();
                Trace.warning( "*** Warning: Unable to Delete [ " +
                               fileList[j].getCanonicalPath() + " ]." );
                Trace.untab();
                deleteFailCount++;                 
            }
      
        } // for( int j = 0; j < fileList.length; j++ )
      
        // If some files couldn't be deleted, there's no point in
        // continuing, so stop gracefully now.
        //
        if( deleteFailCount > 0 ) {
            if( deleteFailCount > 1 )
                Trace.info( "Empty Index not deleted because "    +
                            deleteFailCount + " files could not " +
                            "be removed from index directory." );
            else
                Trace.info( "Empty Index not deleted because "  +
                            "a file could not be removed from " +
                            "index directory." );  
            return;
        }
      
        // Now start with the index directory...
        File dir = idxDirToClean;
      
        // And delete it and all the empty parent directories
        // above it.
        //
        for(;;) {
        
            // If the current directory is not empty, we're done.
            File[] contents = dir.listFiles();
            if( contents.length != 0 ) break;
          
            // Otherwise, hang on to the parent directory for 
            // the current directory.
            //
            File parentDir = dir.getParentFile();
          
            // Try to delete the current directory.
            try { dir.delete(); }
          
            // If we could not, display a warning and end gracefully,
            // since we can't continue to delete parent directories if
            // the current one can't be deleted.
            //
            catch( Exception e ) {
            
                Trace.tab();
                Trace.info( "*** Warning: Unable to delete empty "       +
                            "index directory [" + dir.getCanonicalPath() +
                            "]." );
                Trace.untab();
                return;
          
            } // catch( Exception e )
          
            // Then back up to the parent and repeat.
            dir = parentDir;
      
        } // for(;;)
      
    } // if( docCount == cleanCount )
  
    // The current index isn't empty, but if we deleted a
    // document from it, say so.
    //
    else if( cleanCount == 1 )
        Trace.info( cleanCount + " Incomplete Document Fragment Purged." );
  
    // Likewise, if we deleted more than one document, say so.
    else if( cleanCount >  1 )
        Trace.info( cleanCount + " Incomplete Document Fragments Purged." );
  
    // If we didn't delete any documents from the directory, say so.
    else 
        Trace.info( "No Incomplete Documents Found." );
  
    // If the entire index was deleted, say so.
    if( chunkCount == cleanCount )
        Trace.info( "Empty Index Deleted." );    
    
  } // cleanIndex()
  
} // class IdxTreeCleaner
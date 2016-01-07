/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.replication.id;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

import org.junit.Rule;
import org.junit.Test;

import org.neo4j.graphdb.mockfs.EphemeralFileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.kernel.IdType;
import org.neo4j.kernel.internal.DatabaseHealth;
import org.neo4j.test.TargetDirectory;

import static java.io.File.separator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import static org.neo4j.coreedge.raft.replication.id.InMemoryIdAllocationState.Serializer.NUMBER_OF_BYTES_PER_WRITE;

public class OnDiskIdAllocationStateTest
{
    IdType someType = IdType.NODE;

    @Rule
    public TargetDirectory.TestDirectory testDir = TargetDirectory.testDirForTest( getClass() );

    @Test
    public void shouldMaintainStateGivenAnEmptyInitialStore() throws Exception
    {
        // given
        EphemeralFileSystemAbstraction fsa = new EphemeralFileSystemAbstraction();
        fsa.mkdir( testDir.directory() );

        OnDiskIdAllocationState store = new OnDiskIdAllocationState( fsa, testDir.directory(), 100,
                mock( Supplier.class ) );

        // when
        store.firstUnallocated( someType, 1024 );
        store.logIndex( 0 );

        // then
        assertEquals( 1024, store.firstUnallocated( someType ) );
        assertTrue( fsa.fileExists( store.currentStoreFile() ) );
        assertEquals( NUMBER_OF_BYTES_PER_WRITE, fsa.getFileSize( store.currentStoreFile() ) );
    }

    @Test
    public void shouldRestoreFromExistingState() throws Exception
    {
        // given
        EphemeralFileSystemAbstraction fsa = new EphemeralFileSystemAbstraction();
        fsa.mkdir( testDir.directory() );

        OnDiskIdAllocationState store = new OnDiskIdAllocationState( fsa, testDir.directory(), 1,
                mock( Supplier.class ) );

        store.firstUnallocated( someType, 1024 );
        store.logIndex( 1 );

        // when
        OnDiskIdAllocationState restoredOne = new OnDiskIdAllocationState( fsa, testDir
                .directory(), 1, mock( Supplier.class ) );

        // then
        assertEquals( 1024, restoredOne.firstUnallocated( someType ) );
    }

    @Test
    public void shouldAppendEntriesToStoreFile() throws Exception
    {
        // given
        EphemeralFileSystemAbstraction fsa = new EphemeralFileSystemAbstraction();
        fsa.mkdir( testDir.directory() );

        OnDiskIdAllocationState store = new OnDiskIdAllocationState( fsa, testDir.directory(), 100,
                mock( Supplier.class ) );

        final int numberOfAllocationsToAppend = 3;

        // when
        for ( int i = 1; i <= numberOfAllocationsToAppend; i++ )
        {
            store.firstUnallocated( someType, 1024 * i );
            store.logIndex( i );
        }

        // then
        assertEquals( numberOfAllocationsToAppend * NUMBER_OF_BYTES_PER_WRITE,
                fsa.getFileSize( store.currentStoreFile() ) );
    }

    @Test
    public void shouldSwitchToOtherStoreFileAfterSufficientEntries() throws Exception
    {
        // given
        final int numberOfEntiesBeforeSwitchingFiles = 10;
        EphemeralFileSystemAbstraction fsa = new EphemeralFileSystemAbstraction();
        fsa.mkdir( testDir.directory() );

        OnDiskIdAllocationState store =
                new OnDiskIdAllocationState( fsa, testDir.directory(), numberOfEntiesBeforeSwitchingFiles,
                        mock( Supplier.class ) );

        // when
        for ( int i = 1; i <= numberOfEntiesBeforeSwitchingFiles + 1; i++ )
        {
            store.firstUnallocated( someType, 1024 * i );
            store.logIndex( i );
        }

        // then
        assertEquals( testDir.absolutePath() + separator + "id.allocation.B", store.currentStoreFile()
                .getAbsolutePath() );
        assertEquals( NUMBER_OF_BYTES_PER_WRITE, fsa.getFileSize( store.currentStoreFile() ) );
        assertEquals( numberOfEntiesBeforeSwitchingFiles * NUMBER_OF_BYTES_PER_WRITE,
                fsa.getFileSize( new File( testDir.directory(), "id.allocation.A" ) ) );
    }

    @Test
    public void shouldSwitchToBackToFirstStoreFileAfterSufficientEntries() throws Exception
    {
        // given
        final int numberOfEntiesBeforeSwitchingFiles = 10;
        EphemeralFileSystemAbstraction fsa = new EphemeralFileSystemAbstraction();
        fsa.mkdir( testDir.directory() );

        OnDiskIdAllocationState store =
                new OnDiskIdAllocationState( fsa, testDir.directory(), numberOfEntiesBeforeSwitchingFiles,
                        mock( Supplier.class ) );

        // when
        for ( int i = 1; i <= numberOfEntiesBeforeSwitchingFiles * 2 + 1; i++ )
        {
            store.firstUnallocated( someType, 1024 * i );
            store.logIndex( i );
        }

        // then
        assertEquals( testDir.absolutePath() + separator + "id.allocation.A", store.currentStoreFile()
                .getAbsolutePath() );
        assertEquals( NUMBER_OF_BYTES_PER_WRITE, fsa.getFileSize( store.currentStoreFile() ) );
        assertEquals( NUMBER_OF_BYTES_PER_WRITE,
                fsa.getFileSize( new File( testDir.directory(), "id.allocation.A" ) ) );
        assertEquals( numberOfEntiesBeforeSwitchingFiles * NUMBER_OF_BYTES_PER_WRITE,
                fsa.getFileSize( new File( testDir.directory(), "id.allocation.B" ) ) );
    }

    @Test
    public void shouldRestoreLastStateOutOfManyWrittenOnSingleFile() throws Exception
    {
        // given
        final int numberOfEntiesBeforeSwitchingFiles = 10;
        EphemeralFileSystemAbstraction fsa = new EphemeralFileSystemAbstraction();
        fsa.mkdir( testDir.directory() );

        OnDiskIdAllocationState store =
                new OnDiskIdAllocationState( fsa, testDir.directory(), numberOfEntiesBeforeSwitchingFiles,
                        mock( Supplier.class ) );

        // and a store that has two entries in the store file
        for ( int i = 1; i < 3; i++ )
        {
            store.lastIdRangeLength( someType, i * 1024 );
            store.logIndex( i );
        }

        // then
        // we restore it and see the last of the two entries
        OnDiskIdAllocationState restoredOne = new OnDiskIdAllocationState( fsa, testDir.directory(), 1,
                mock( Supplier.class ) );

        assertEquals( 1024 * 2, restoredOne.lastIdRangeLength( someType ) );
        assertEquals( 2, restoredOne.logIndex() );
    }

    @Test
    public void shouldRecoverFromExistingStoreFiles() throws Exception
    {
        // given
        EphemeralFileSystemAbstraction fsa = new EphemeralFileSystemAbstraction();
        fsa.mkdir( testDir.directory() );

        OnDiskIdAllocationState store = new OnDiskIdAllocationState( fsa, testDir.directory(), 10,
                mock( Supplier.class ) );

        for ( int i = 1; i <= 3; i++ )
        {
            store.firstUnallocated( someType, 1024 * i );
            store.logIndex( i );
        }

        // when
        store = new OnDiskIdAllocationState( fsa, testDir.directory(), 10, mock( Supplier.class ) );
        store.firstUnallocated( someType, 1024 * 4 );
        store.logIndex( 4 );

        // then
        assertEquals( 4 * NUMBER_OF_BYTES_PER_WRITE,
                fsa.getFileSize( new File( testDir.directory(), "id.allocation.A" ) ) );
    }

    @Test
    public void shouldPanicIfCannotPersistToDisk() throws Exception
    {
        // given
        EphemeralFileSystemAbstraction fsa = new ExplodingFileSystemAbstraction();
        fsa.mkdir( testDir.directory() );

        final DatabaseHealth databaseHealth = mock( DatabaseHealth.class );
        Supplier<DatabaseHealth> supplier = () -> databaseHealth;

        OnDiskIdAllocationState store = new OnDiskIdAllocationState( fsa, testDir.directory(), 100, supplier );

        // when
        store.logIndex( 99 );

        // then
        verify( databaseHealth ).panic( any( Throwable.class ) );
    }

    private class ExplodingFileSystemAbstraction extends EphemeralFileSystemAbstraction
    {
        @Override
        public synchronized StoreChannel open( File fileName, String mode ) throws IOException
        {
            final StoreChannel mock = mock( StoreChannel.class );
            doThrow( new IOException( "boom!" ) ).when( mock ).force( false );
            return mock;
        }
    }
}
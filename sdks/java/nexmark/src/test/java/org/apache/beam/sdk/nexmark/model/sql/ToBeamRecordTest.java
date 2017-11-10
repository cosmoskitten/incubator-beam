/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.beam.sdk.nexmark.model.sql;

import org.apache.beam.sdk.nexmark.model.Auction;
import org.apache.beam.sdk.nexmark.model.Bid;
import org.apache.beam.sdk.nexmark.model.Person;
import org.apache.beam.sdk.nexmark.model.SellerPrice;
import org.apache.beam.sdk.nexmark.model.sql.adapter.ModelFieldsAdapters;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.TestStream;
import org.apache.beam.sdk.values.BeamRecord;
import org.apache.beam.sdk.values.PCollection;

import org.hamcrest.core.IsInstanceOf;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/**
 * Unit tests for {@link ToBeamRecord}.
 */
public class ToBeamRecordTest {
  private static final Person PERSON =
      new Person(3L, "name", "email", "cc", "city", "state", 329823L, "extra");

  private static final Bid BID =
      new Bid(5L, 3L, 123123L, 43234234L, "extra2");

  private static final Auction AUCTION =
      new Auction(5L, "item", "desc", 342L, 321L, 3423342L, 2349234L, 3L, 1L, "extra3");

  private static final SellerPrice SELLER_PRICE =
      new SellerPrice(32L, 934L);

  @Rule public TestPipeline testPipeline = TestPipeline.create();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testCovertsBids() throws Exception {
    PCollection<Bid> bids = testPipeline.apply(
        TestStream.create(Bid.CODER)
        .addElements(BID)
        .advanceWatermarkToInfinity());

    BeamRecord expectedBidRecord =
        new BeamRecord(
            ModelFieldsAdapters.ADAPTERS.get(Bid.class).getRecordType(),
            ModelFieldsAdapters.ADAPTERS.get(Bid.class).getFieldsValues(BID));

    PAssert
        .that(bids.apply(ToBeamRecord.parDo()))
        .containsInAnyOrder(expectedBidRecord);

    testPipeline.run();
  }

  @Test
  public void testCovertsPeople() throws Exception {
    PCollection<Person> people = testPipeline.apply(
        TestStream.create(Person.CODER)
            .addElements(PERSON)
            .advanceWatermarkToInfinity());

    BeamRecord expectedPersonRecord =
        new BeamRecord(
            ModelFieldsAdapters.ADAPTERS.get(Person.class).getRecordType(),
            ModelFieldsAdapters.ADAPTERS.get(Person.class).getFieldsValues(PERSON));

    PAssert
        .that(people.apply(ToBeamRecord.parDo()))
        .containsInAnyOrder(expectedPersonRecord);

    testPipeline.run();
  }

  @Test
  public void testCovertsAuctions() throws Exception {
    PCollection<Auction> auctions = testPipeline.apply(
        TestStream.create(Auction.CODER)
            .addElements(AUCTION)
            .advanceWatermarkToInfinity());

    BeamRecord expectedAuctionRecord =
        new BeamRecord(
            ModelFieldsAdapters.ADAPTERS.get(Auction.class).getRecordType(),
            ModelFieldsAdapters.ADAPTERS.get(Auction.class).getFieldsValues(AUCTION));

    PAssert
        .that(auctions.apply(ToBeamRecord.parDo()))
        .containsInAnyOrder(expectedAuctionRecord);

    testPipeline.run();
  }

  @Test
  public void testThrowsForUnknownModel() throws Exception {

    thrown.expectCause(IsInstanceOf.<Throwable> instanceOf(IllegalArgumentException.class));

    PCollection<SellerPrice> sellerPrices = testPipeline.apply(
        TestStream.create(SellerPrice.CODER)
            .addElements(SELLER_PRICE)
            .advanceWatermarkToInfinity());

    sellerPrices.apply(ToBeamRecord.parDo());

    testPipeline.run();
  }


}

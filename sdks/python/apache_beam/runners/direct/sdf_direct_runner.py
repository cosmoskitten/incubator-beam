#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""This module contains Splittable DoFn logic that is specific to DirectRunner.
"""

from threading import Lock
from threading import Timer

import apache_beam as beam
from apache_beam import TimeDomain
from apache_beam import pvalue
from apache_beam.io.iobase import RestrictionTracker
from apache_beam.pipeline import PTransformOverride
from apache_beam.runners.common import DoFnContext
from apache_beam.runners.common import DoFnInvoker
from apache_beam.runners.common import DoFnSignature
from apache_beam.runners.common import OutputProcessor
from apache_beam.runners.direct.evaluation_context import DirectStepContext
from apache_beam.runners.direct.util import KeyedWorkItem
from apache_beam.runners.direct.watermark_manager import WatermarkManager
from apache_beam.runners.sdf_common import ElementAndRestriction
from apache_beam.runners.sdf_common import ProcessKeyedElements
from apache_beam.transforms.core import ProcessContinuation
from apache_beam.transforms.ptransform import PTransform
from apache_beam.transforms.trigger import _ValueStateTag
from apache_beam.utils.windowed_value import WindowedValue


class ProcessKeyedElementsViaKeyedWorkItemsOverride(PTransformOverride):
  """A transform override for ProcessElements transform."""

  def matches(self, applied_ptransform):
    return isinstance(
        applied_ptransform.transform, ProcessKeyedElements)

  def get_replacement_transform(self, ptransform):
    return ProcessKeyedElementsViaKeyedWorkItems(ptransform)


class ProcessKeyedElementsViaKeyedWorkItems(PTransform):
  """A transform that processes Splittable DoFn input via KeyedWorkItems."""

  def __init__(self, process_keyed_elements_transform):
    self._process_keyed_elements_transform = process_keyed_elements_transform

  def expand(self, pcoll):
    return pcoll | beam.core.GroupByKey() | ProcessElements(
        self._process_keyed_elements_transform)


class ProcessElements(PTransform):
  """A primitive transform for processing keyed elements or KeyedWorkItems.

  Will be evaluated by
  `runners.direct.transform_evaluator._ProcessElementsEvaluator`.
  """

  def __init__(self, process_keyed_elements_transform):
    self._process_keyed_elements_transform = process_keyed_elements_transform
    self.sdf = self._process_keyed_elements_transform.sdf

  def expand(self, pcoll):
    return pvalue.PCollection(pcoll.pipeline)

  def new_process_fn(self, sdf):
    return ProcessFn(
        sdf,
        self._process_keyed_elements_transform.ptransform_args,
        self._process_keyed_elements_transform.ptransform_kwargs)


class ProcessFn(beam.DoFn):
  """A `DoFn` that executes machineary for invoking a Splittable `DoFn`.

  Input to the `ParDo` step that includes a `ProcessFn` will be a `PCollection`
  of `ElementAndRestriction` objects.

  This class is mainly responsible for following.
  (1) setup environment for properly invoking a Splittable `DoFn`.
  (2) invoke `process()` method of a Splittable `DoFn`.
  (3) after the `process()` invocation of the Splittable `DoFn`, determine if a
  re-invocation of the element is needed. If this is the case, set state and
  a timer for a re-invocation and hold output watermark till this
  re-invocation.
  (4) after the final invocation of a given element clear any previous state set
  for re-invoking the element and release the output watermark.
  """

  def __init__(
      self, sdf, args_for_invoker, kwargs_for_invoker):
    self.sdf = sdf
    self._element_tag = _ValueStateTag('element')
    self._restriction_tag = _ValueStateTag('restriction')
    self.watermark_hold_tag = _ValueStateTag('watermark_hold')
    self._process_element_invoker = None

    self.sdf_invoker = DoFnInvoker.create_invoker(
        DoFnSignature(self.sdf), context=DoFnContext('unused_context'),
        input_args=args_for_invoker, input_kwargs=kwargs_for_invoker)

    self._step_context = None

  @property
  def step_context(self):
    return self._step_context

  @step_context.setter
  def step_context(self, step_context):
    assert isinstance(step_context, DirectStepContext)
    self._step_context = step_context

  def set_process_element_invoker(self, process_element_invoker):
    assert isinstance(process_element_invoker, SDFProcessElementInvoker)
    self._process_element_invoker = process_element_invoker

  def process(self, element, timestamp=beam.DoFn.TimestampParam,
              window=beam.DoFn.WindowParam, *args, **kwargs):
    if isinstance(element, KeyedWorkItem):
      # Must be a timer firing.
      key = element.encoded_key
    else:
      key, values = element
      values = list(values)
      assert len(values) == 1
      # Value here will either be a WindowedValue or an ElementAndRestriction
      # object.
      # TODO: handle key collisions here.
      assert len(values) == 1, 'Internal error. Processing of splittable ' \
                               'DoFn cannot continue since elements did not ' \
                               'have unique keys.'
      value = values[0]
      if len(values) != 1:
        raise ValueError('')

    state = self._step_context.get_keyed_state(key)
    element_state = state.get_state(window, self._element_tag)
    # Initially element_state is an empty list.
    is_seed_call = not element_state

    if not is_seed_call:
      element = state.get_state(window, self._element_tag)
      restriction = state.get_state(window, self._restriction_tag)
      windowed_element = WindowedValue(element, timestamp, [window])
    else:
      # After values iterator is expanded above we should have gotten a list
      # with a single ElementAndRestriction object.
      assert isinstance(value, ElementAndRestriction)
      element_and_restriction = value
      element = element_and_restriction.element
      restriction = element_and_restriction.restriction

      if isinstance(value, WindowedValue):
        windowed_element = WindowedValue(
            element, value.timestamp, value.windows)
      else:
        windowed_element = WindowedValue(element, timestamp, [window])

    tracker = self.sdf_invoker.invoke_create_tracker(restriction)
    assert self._process_element_invoker
    assert isinstance(self._process_element_invoker,
                      SDFProcessElementInvoker)

    output_values = self._process_element_invoker.invoke_process_element(
        self.sdf_invoker, windowed_element, tracker)

    sdf_result = None
    for output in output_values:
      if isinstance(output, SDFProcessElementInvoker.Result):
        # SDFProcessElementInvoker.Result should be the last item yielded.
        sdf_result = output
        break
      yield output

    assert sdf_result, ('SDFProcessElementInvoker must return a '
                        'SDFProcessElementInvoker.Result object as the last '
                        'value of a SDF invoke_process_element() invocation.')

    if not sdf_result.residual_restriction:
      # All work for current residual and restriction pair is complete.
      state.clear_state(window, self._element_tag)
      state.clear_state(window, self._restriction_tag)
      # Releasing output watermark by setting it to positive infinity.
      state.add_state(window, self.watermark_hold_tag,
                      WatermarkManager.WATERMARK_POS_INF)
    else:
      state.add_state(window, self._element_tag, element)
      state.add_state(window, self._restriction_tag,
                      sdf_result.residual_restriction)
      # Holding output watermark by setting it to negative infinity.
      state.add_state(window, self.watermark_hold_tag,
                      WatermarkManager.WATERMARK_NEG_INF)

      # Setting a timer to be reinvoked to continue processing the element.
      # Currently Python SDK only supports setting timers based on watermark. So
      # forcing a reinvocation by setting a timer for watermark negative
      # infinity.
      # TODO(chamikara): update this by setting a timer for the proper
      # processing time when Python SDK supports that.
      state.set_timer(
          window, '', TimeDomain.WATERMARK, WatermarkManager.WATERMARK_NEG_INF)


class SDFProcessElementInvoker(object):
  """A utility that invokes SDF `process()` method and requests checkpoints.

  This class is responsible for invoking the `process()` method of a Splittable
  `DoFn` and making sure that invocation terminated properly. Based on the input
  configuration, this class may decide to request a checkpoint for a `process()`
  execution so that runner can process current output and resume the invocation
  at a later time.

  More specifically, when initializing a `SDFProcessElementInvoker`, caller may
  specify the number of output elements or processing time after which a
  checkpoint should be requested. This class is responsible for properly
  requesting a checkpoint based on either of these criteria.
  When the `process()` call of Splittable `DoFn` ends, this class performs
  validations to make sure that processing ended gracefully and returns a
  `SDFProcessElementInvoker.Result` that contains information which can be used
  by the caller to perform another `process()` invocation for the residual.

  A `process()` invocation may decide to give up processing voluntarily by
  returning a `ProcessContinuation` object (see documentation of
  `ProcessContinuation` for more details). So if a 'ProcessContinuation' is
  produced this class ends the execution and performs steps to finalize the
  current invocation.
  """

  class Result(object):
    def __init__(
        self, residual_restriction=None, process_continuation=None,
        future_output_watermark=None):
      """Returned as a result of a `invoke_process_element()` invocation.

      Args:
        residual_restriction: a restriction for the unprocessed part of the
                             element.
        process_continuation: a `ProcessContinuation` if one was returned as the
                              last element of the SDF `process()` invocation.
        future_output_watermark: output watermark of the results that will be
                                 produced when invoking the Splittable `DoFn`
                                 for the current element with
                                 `residual_restriction`.
      """

      self.residual_restriction = residual_restriction
      self.process_continuation = process_continuation
      self.future_output_watermark = future_output_watermark

  def __init__(
      self, max_num_outputs, max_duration):
    self._max_num_outputs = max_num_outputs
    self._max_duration = max_duration
    self._checkpoint_lock = Lock()

  def test_method(self):
    raise ValueError

  def invoke_process_element(self, sdf_invoker, element, tracker):
    """Invokes `process()` method of a Splittable `DoFn` for a given element.

     Args:
       sdf_invoker: a `DoFnInvoker` for the Splittable `DoFn`.
       element: the element to process
       tracker: a `RestrictionTracker` for the element that will be passed when
                invoking the `process()` method of the Splittable `DoFn`.
     Returns:
       a `SDFProcessElementInvoker.Result` object.
     """
    assert isinstance(sdf_invoker, DoFnInvoker)
    assert isinstance(tracker, RestrictionTracker)

    class CheckpointState(object):

      def __init__(self):
        self.checkpointed = None
        self.residual_restriction = None

    checkpoint_state = CheckpointState()

    def initiate_checkpoint():
      with self._checkpoint_lock:
        if checkpoint_state.checkpointed:
          return
      checkpoint_state.residual_restriction = tracker.checkpoint()
      checkpoint_state.checkpointed = object()

    output_processor = _OutputProcessor()
    Timer(self._max_duration, initiate_checkpoint).start()
    sdf_invoker.invoke_process(
        element, restriction_tracker=tracker, output_processor=output_processor)

    assert output_processor.output_iter is not None
    output_count = 0

    # We have to expand and re-yield here to support ending execution for a
    # given number of output elements as well as to capture the
    # ProcessContinuation of one was returned.
    process_continuation = None
    for output in output_processor.output_iter:
      # A ProcessContinuation, if returned, should be the last element.
      assert not process_continuation
      if isinstance(output, ProcessContinuation):
        # Taking a checkpoint so that we can determine primary and residual
        # restrictions.
        initiate_checkpoint()

        # A ProcessContinuation should always be the last element produced by
        # the output iterator.
        # TODO: support continuing after the specified amount of delay.

        # Continuing here instead of breaking to enforce that this is the last
        # element.
        process_continuation = output
        continue

      yield output
      output_count += 1
      if self._max_num_outputs and output_count >= self._max_num_outputs:
        initiate_checkpoint()

    tracker.check_done()
    result = (
        SDFProcessElementInvoker.Result(
            residual_restriction=checkpoint_state.residual_restriction)
        if checkpoint_state.residual_restriction
        else SDFProcessElementInvoker.Result())
    yield result


class _OutputProcessor(OutputProcessor):

  def __init__(self):
    self.output_iter = None

  def process_outputs(self, windowed_input_element, output_iter):
    self.output_iter = output_iter

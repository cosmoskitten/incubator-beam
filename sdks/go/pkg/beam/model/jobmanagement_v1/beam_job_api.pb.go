// Code generated by protoc-gen-go. DO NOT EDIT.
// source: beam_job_api.proto

/*
Package jobmanagement_v1 is a generated protocol buffer package.

It is generated from these files:
	beam_job_api.proto
	beam_artifact_api.proto

It has these top-level messages:
	PrepareJobRequest
	PrepareJobResponse
	RunJobRequest
	RunJobResponse
	CancelJobRequest
	CancelJobResponse
	GetJobStateRequest
	GetJobStateResponse
	JobMessagesRequest
	JobMessage
	JobMessagesResponse
	JobState
	ArtifactMetadata
	Manifest
	ProxyManifest
	GetManifestRequest
	GetManifestResponse
	GetArtifactRequest
	ArtifactChunk
	PutArtifactRequest
	PutArtifactResponse
	CommitManifestRequest
	CommitManifestResponse
*/
package jobmanagement_v1

import proto "github.com/golang/protobuf/proto"
import fmt "fmt"
import math "math"
import org_apache_beam_model_pipeline_v1 "github.com/apache/beam/sdks/go/pkg/beam/model/pipeline_v1"
import org_apache_beam_model_pipeline_v11 "github.com/apache/beam/sdks/go/pkg/beam/model/pipeline_v1"
import google_protobuf1 "github.com/golang/protobuf/ptypes/struct"

import (
	context "golang.org/x/net/context"
	grpc "google.golang.org/grpc"
)

// Reference imports to suppress errors if they are not otherwise used.
var _ = proto.Marshal
var _ = fmt.Errorf
var _ = math.Inf

// This is a compile-time assertion to ensure that this generated file
// is compatible with the proto package it is being compiled against.
// A compilation error at this line likely means your copy of the
// proto package needs to be updated.
const _ = proto.ProtoPackageIsVersion2 // please upgrade the proto package

type JobMessage_MessageImportance int32

const (
	JobMessage_MESSAGE_IMPORTANCE_UNSPECIFIED JobMessage_MessageImportance = 0
	JobMessage_JOB_MESSAGE_DEBUG              JobMessage_MessageImportance = 1
	JobMessage_JOB_MESSAGE_DETAILED           JobMessage_MessageImportance = 2
	JobMessage_JOB_MESSAGE_BASIC              JobMessage_MessageImportance = 3
	JobMessage_JOB_MESSAGE_WARNING            JobMessage_MessageImportance = 4
	JobMessage_JOB_MESSAGE_ERROR              JobMessage_MessageImportance = 5
)

var JobMessage_MessageImportance_name = map[int32]string{
	0: "MESSAGE_IMPORTANCE_UNSPECIFIED",
	1: "JOB_MESSAGE_DEBUG",
	2: "JOB_MESSAGE_DETAILED",
	3: "JOB_MESSAGE_BASIC",
	4: "JOB_MESSAGE_WARNING",
	5: "JOB_MESSAGE_ERROR",
}
var JobMessage_MessageImportance_value = map[string]int32{
	"MESSAGE_IMPORTANCE_UNSPECIFIED": 0,
	"JOB_MESSAGE_DEBUG":              1,
	"JOB_MESSAGE_DETAILED":           2,
	"JOB_MESSAGE_BASIC":              3,
	"JOB_MESSAGE_WARNING":            4,
	"JOB_MESSAGE_ERROR":              5,
}

func (x JobMessage_MessageImportance) String() string {
	return proto.EnumName(JobMessage_MessageImportance_name, int32(x))
}
func (JobMessage_MessageImportance) EnumDescriptor() ([]byte, []int) {
	return fileDescriptor0, []int{9, 0}
}

type JobState_Enum int32

const (
	JobState_UNSPECIFIED JobState_Enum = 0
	JobState_STOPPED     JobState_Enum = 1
	JobState_RUNNING     JobState_Enum = 2
	JobState_DONE        JobState_Enum = 3
	JobState_FAILED      JobState_Enum = 4
	JobState_CANCELLED   JobState_Enum = 5
	JobState_UPDATED     JobState_Enum = 6
	JobState_DRAINING    JobState_Enum = 7
	JobState_DRAINED     JobState_Enum = 8
	JobState_STARTING    JobState_Enum = 9
	JobState_CANCELLING  JobState_Enum = 10
)

var JobState_Enum_name = map[int32]string{
	0:  "UNSPECIFIED",
	1:  "STOPPED",
	2:  "RUNNING",
	3:  "DONE",
	4:  "FAILED",
	5:  "CANCELLED",
	6:  "UPDATED",
	7:  "DRAINING",
	8:  "DRAINED",
	9:  "STARTING",
	10: "CANCELLING",
}
var JobState_Enum_value = map[string]int32{
	"UNSPECIFIED": 0,
	"STOPPED":     1,
	"RUNNING":     2,
	"DONE":        3,
	"FAILED":      4,
	"CANCELLED":   5,
	"UPDATED":     6,
	"DRAINING":    7,
	"DRAINED":     8,
	"STARTING":    9,
	"CANCELLING":  10,
}

func (x JobState_Enum) String() string {
	return proto.EnumName(JobState_Enum_name, int32(x))
}
func (JobState_Enum) EnumDescriptor() ([]byte, []int) { return fileDescriptor0, []int{11, 0} }

// Prepare is a synchronous request that returns a preparationId back
// Throws error GRPC_STATUS_UNAVAILABLE if server is down
// Throws error ALREADY_EXISTS if the jobName is reused. Runners are permitted to deduplicate based on the name of the job.
// Throws error UNKNOWN for all other issues
type PrepareJobRequest struct {
	Pipeline        *org_apache_beam_model_pipeline_v1.Pipeline `protobuf:"bytes,1,opt,name=pipeline" json:"pipeline,omitempty"`
	PipelineOptions *google_protobuf1.Struct                    `protobuf:"bytes,2,opt,name=pipeline_options,json=pipelineOptions" json:"pipeline_options,omitempty"`
	JobName         string                                      `protobuf:"bytes,3,opt,name=job_name,json=jobName" json:"job_name,omitempty"`
}

func (m *PrepareJobRequest) Reset()                    { *m = PrepareJobRequest{} }
func (m *PrepareJobRequest) String() string            { return proto.CompactTextString(m) }
func (*PrepareJobRequest) ProtoMessage()               {}
func (*PrepareJobRequest) Descriptor() ([]byte, []int) { return fileDescriptor0, []int{0} }

func (m *PrepareJobRequest) GetPipeline() *org_apache_beam_model_pipeline_v1.Pipeline {
	if m != nil {
		return m.Pipeline
	}
	return nil
}

func (m *PrepareJobRequest) GetPipelineOptions() *google_protobuf1.Struct {
	if m != nil {
		return m.PipelineOptions
	}
	return nil
}

func (m *PrepareJobRequest) GetJobName() string {
	if m != nil {
		return m.JobName
	}
	return ""
}

type PrepareJobResponse struct {
	// (required) The ID used to associate calls made while preparing the job. preparationId is used
	// to run the job, as well as in other pre-execution APIs such as Artifact staging.
	PreparationId string `protobuf:"bytes,1,opt,name=preparation_id,json=preparationId" json:"preparation_id,omitempty"`
	// An endpoint which exposes the Beam Artifact Staging API. Artifacts used by the job should be
	// staged to this endpoint, and will be available during job execution.
	ArtifactStagingEndpoint *org_apache_beam_model_pipeline_v11.ApiServiceDescriptor `protobuf:"bytes,2,opt,name=artifact_staging_endpoint,json=artifactStagingEndpoint" json:"artifact_staging_endpoint,omitempty"`
}

func (m *PrepareJobResponse) Reset()                    { *m = PrepareJobResponse{} }
func (m *PrepareJobResponse) String() string            { return proto.CompactTextString(m) }
func (*PrepareJobResponse) ProtoMessage()               {}
func (*PrepareJobResponse) Descriptor() ([]byte, []int) { return fileDescriptor0, []int{1} }

func (m *PrepareJobResponse) GetPreparationId() string {
	if m != nil {
		return m.PreparationId
	}
	return ""
}

func (m *PrepareJobResponse) GetArtifactStagingEndpoint() *org_apache_beam_model_pipeline_v11.ApiServiceDescriptor {
	if m != nil {
		return m.ArtifactStagingEndpoint
	}
	return nil
}

// Run is a synchronous request that returns a jobId back.
// Throws error GRPC_STATUS_UNAVAILABLE if server is down
// Throws error NOT_FOUND if the preparation ID does not exist
// Throws error UNKNOWN for all other issues
type RunJobRequest struct {
	// (required) The ID provided by an earlier call to prepare. Runs the job. All prerequisite tasks
	// must have been completed.
	PreparationId string `protobuf:"bytes,1,opt,name=preparation_id,json=preparationId" json:"preparation_id,omitempty"`
	// (optional) If any artifacts have been staged for this job, contains the staging_token returned
	// from the CommitManifestResponse.
	StagingToken string `protobuf:"bytes,2,opt,name=staging_token,json=stagingToken" json:"staging_token,omitempty"`
}

func (m *RunJobRequest) Reset()                    { *m = RunJobRequest{} }
func (m *RunJobRequest) String() string            { return proto.CompactTextString(m) }
func (*RunJobRequest) ProtoMessage()               {}
func (*RunJobRequest) Descriptor() ([]byte, []int) { return fileDescriptor0, []int{2} }

func (m *RunJobRequest) GetPreparationId() string {
	if m != nil {
		return m.PreparationId
	}
	return ""
}

func (m *RunJobRequest) GetStagingToken() string {
	if m != nil {
		return m.StagingToken
	}
	return ""
}

type RunJobResponse struct {
	JobId string `protobuf:"bytes,1,opt,name=job_id,json=jobId" json:"job_id,omitempty"`
}

func (m *RunJobResponse) Reset()                    { *m = RunJobResponse{} }
func (m *RunJobResponse) String() string            { return proto.CompactTextString(m) }
func (*RunJobResponse) ProtoMessage()               {}
func (*RunJobResponse) Descriptor() ([]byte, []int) { return fileDescriptor0, []int{3} }

func (m *RunJobResponse) GetJobId() string {
	if m != nil {
		return m.JobId
	}
	return ""
}

// Cancel is a synchronus request that returns a job state back
// Throws error GRPC_STATUS_UNAVAILABLE if server is down
// Throws error NOT_FOUND if the jobId is not found
type CancelJobRequest struct {
	JobId string `protobuf:"bytes,1,opt,name=job_id,json=jobId" json:"job_id,omitempty"`
}

func (m *CancelJobRequest) Reset()                    { *m = CancelJobRequest{} }
func (m *CancelJobRequest) String() string            { return proto.CompactTextString(m) }
func (*CancelJobRequest) ProtoMessage()               {}
func (*CancelJobRequest) Descriptor() ([]byte, []int) { return fileDescriptor0, []int{4} }

func (m *CancelJobRequest) GetJobId() string {
	if m != nil {
		return m.JobId
	}
	return ""
}

// Valid responses include any terminal state or CANCELLING
type CancelJobResponse struct {
	State JobState_Enum `protobuf:"varint,1,opt,name=state,enum=org.apache.beam.model.job_management.v1.JobState_Enum" json:"state,omitempty"`
}

func (m *CancelJobResponse) Reset()                    { *m = CancelJobResponse{} }
func (m *CancelJobResponse) String() string            { return proto.CompactTextString(m) }
func (*CancelJobResponse) ProtoMessage()               {}
func (*CancelJobResponse) Descriptor() ([]byte, []int) { return fileDescriptor0, []int{5} }

func (m *CancelJobResponse) GetState() JobState_Enum {
	if m != nil {
		return m.State
	}
	return JobState_UNSPECIFIED
}

// GetState is a synchronus request that returns a job state back
// Throws error GRPC_STATUS_UNAVAILABLE if server is down
// Throws error NOT_FOUND if the jobId is not found
type GetJobStateRequest struct {
	JobId string `protobuf:"bytes,1,opt,name=job_id,json=jobId" json:"job_id,omitempty"`
}

func (m *GetJobStateRequest) Reset()                    { *m = GetJobStateRequest{} }
func (m *GetJobStateRequest) String() string            { return proto.CompactTextString(m) }
func (*GetJobStateRequest) ProtoMessage()               {}
func (*GetJobStateRequest) Descriptor() ([]byte, []int) { return fileDescriptor0, []int{6} }

func (m *GetJobStateRequest) GetJobId() string {
	if m != nil {
		return m.JobId
	}
	return ""
}

type GetJobStateResponse struct {
	State JobState_Enum `protobuf:"varint,1,opt,name=state,enum=org.apache.beam.model.job_management.v1.JobState_Enum" json:"state,omitempty"`
}

func (m *GetJobStateResponse) Reset()                    { *m = GetJobStateResponse{} }
func (m *GetJobStateResponse) String() string            { return proto.CompactTextString(m) }
func (*GetJobStateResponse) ProtoMessage()               {}
func (*GetJobStateResponse) Descriptor() ([]byte, []int) { return fileDescriptor0, []int{7} }

func (m *GetJobStateResponse) GetState() JobState_Enum {
	if m != nil {
		return m.State
	}
	return JobState_UNSPECIFIED
}

// GetJobMessages is a streaming api for streaming job messages from the service
// One request will connect you to the job and you'll get a stream of job state
// and job messages back; one is used for logging and the other for detecting
// the job ended.
type JobMessagesRequest struct {
	JobId string `protobuf:"bytes,1,opt,name=job_id,json=jobId" json:"job_id,omitempty"`
}

func (m *JobMessagesRequest) Reset()                    { *m = JobMessagesRequest{} }
func (m *JobMessagesRequest) String() string            { return proto.CompactTextString(m) }
func (*JobMessagesRequest) ProtoMessage()               {}
func (*JobMessagesRequest) Descriptor() ([]byte, []int) { return fileDescriptor0, []int{8} }

func (m *JobMessagesRequest) GetJobId() string {
	if m != nil {
		return m.JobId
	}
	return ""
}

type JobMessage struct {
	MessageId   string                       `protobuf:"bytes,1,opt,name=message_id,json=messageId" json:"message_id,omitempty"`
	Time        string                       `protobuf:"bytes,2,opt,name=time" json:"time,omitempty"`
	Importance  JobMessage_MessageImportance `protobuf:"varint,3,opt,name=importance,enum=org.apache.beam.model.job_management.v1.JobMessage_MessageImportance" json:"importance,omitempty"`
	MessageText string                       `protobuf:"bytes,4,opt,name=message_text,json=messageText" json:"message_text,omitempty"`
}

func (m *JobMessage) Reset()                    { *m = JobMessage{} }
func (m *JobMessage) String() string            { return proto.CompactTextString(m) }
func (*JobMessage) ProtoMessage()               {}
func (*JobMessage) Descriptor() ([]byte, []int) { return fileDescriptor0, []int{9} }

func (m *JobMessage) GetMessageId() string {
	if m != nil {
		return m.MessageId
	}
	return ""
}

func (m *JobMessage) GetTime() string {
	if m != nil {
		return m.Time
	}
	return ""
}

func (m *JobMessage) GetImportance() JobMessage_MessageImportance {
	if m != nil {
		return m.Importance
	}
	return JobMessage_MESSAGE_IMPORTANCE_UNSPECIFIED
}

func (m *JobMessage) GetMessageText() string {
	if m != nil {
		return m.MessageText
	}
	return ""
}

type JobMessagesResponse struct {
	// Types that are valid to be assigned to Response:
	//	*JobMessagesResponse_MessageResponse
	//	*JobMessagesResponse_StateResponse
	Response isJobMessagesResponse_Response `protobuf_oneof:"response"`
}

func (m *JobMessagesResponse) Reset()                    { *m = JobMessagesResponse{} }
func (m *JobMessagesResponse) String() string            { return proto.CompactTextString(m) }
func (*JobMessagesResponse) ProtoMessage()               {}
func (*JobMessagesResponse) Descriptor() ([]byte, []int) { return fileDescriptor0, []int{10} }

type isJobMessagesResponse_Response interface {
	isJobMessagesResponse_Response()
}

type JobMessagesResponse_MessageResponse struct {
	MessageResponse *JobMessage `protobuf:"bytes,1,opt,name=message_response,json=messageResponse,oneof"`
}
type JobMessagesResponse_StateResponse struct {
	StateResponse *GetJobStateResponse `protobuf:"bytes,2,opt,name=state_response,json=stateResponse,oneof"`
}

func (*JobMessagesResponse_MessageResponse) isJobMessagesResponse_Response() {}
func (*JobMessagesResponse_StateResponse) isJobMessagesResponse_Response()   {}

func (m *JobMessagesResponse) GetResponse() isJobMessagesResponse_Response {
	if m != nil {
		return m.Response
	}
	return nil
}

func (m *JobMessagesResponse) GetMessageResponse() *JobMessage {
	if x, ok := m.GetResponse().(*JobMessagesResponse_MessageResponse); ok {
		return x.MessageResponse
	}
	return nil
}

func (m *JobMessagesResponse) GetStateResponse() *GetJobStateResponse {
	if x, ok := m.GetResponse().(*JobMessagesResponse_StateResponse); ok {
		return x.StateResponse
	}
	return nil
}

// XXX_OneofFuncs is for the internal use of the proto package.
func (*JobMessagesResponse) XXX_OneofFuncs() (func(msg proto.Message, b *proto.Buffer) error, func(msg proto.Message, tag, wire int, b *proto.Buffer) (bool, error), func(msg proto.Message) (n int), []interface{}) {
	return _JobMessagesResponse_OneofMarshaler, _JobMessagesResponse_OneofUnmarshaler, _JobMessagesResponse_OneofSizer, []interface{}{
		(*JobMessagesResponse_MessageResponse)(nil),
		(*JobMessagesResponse_StateResponse)(nil),
	}
}

func _JobMessagesResponse_OneofMarshaler(msg proto.Message, b *proto.Buffer) error {
	m := msg.(*JobMessagesResponse)
	// response
	switch x := m.Response.(type) {
	case *JobMessagesResponse_MessageResponse:
		b.EncodeVarint(1<<3 | proto.WireBytes)
		if err := b.EncodeMessage(x.MessageResponse); err != nil {
			return err
		}
	case *JobMessagesResponse_StateResponse:
		b.EncodeVarint(2<<3 | proto.WireBytes)
		if err := b.EncodeMessage(x.StateResponse); err != nil {
			return err
		}
	case nil:
	default:
		return fmt.Errorf("JobMessagesResponse.Response has unexpected type %T", x)
	}
	return nil
}

func _JobMessagesResponse_OneofUnmarshaler(msg proto.Message, tag, wire int, b *proto.Buffer) (bool, error) {
	m := msg.(*JobMessagesResponse)
	switch tag {
	case 1: // response.message_response
		if wire != proto.WireBytes {
			return true, proto.ErrInternalBadWireType
		}
		msg := new(JobMessage)
		err := b.DecodeMessage(msg)
		m.Response = &JobMessagesResponse_MessageResponse{msg}
		return true, err
	case 2: // response.state_response
		if wire != proto.WireBytes {
			return true, proto.ErrInternalBadWireType
		}
		msg := new(GetJobStateResponse)
		err := b.DecodeMessage(msg)
		m.Response = &JobMessagesResponse_StateResponse{msg}
		return true, err
	default:
		return false, nil
	}
}

func _JobMessagesResponse_OneofSizer(msg proto.Message) (n int) {
	m := msg.(*JobMessagesResponse)
	// response
	switch x := m.Response.(type) {
	case *JobMessagesResponse_MessageResponse:
		s := proto.Size(x.MessageResponse)
		n += proto.SizeVarint(1<<3 | proto.WireBytes)
		n += proto.SizeVarint(uint64(s))
		n += s
	case *JobMessagesResponse_StateResponse:
		s := proto.Size(x.StateResponse)
		n += proto.SizeVarint(2<<3 | proto.WireBytes)
		n += proto.SizeVarint(uint64(s))
		n += s
	case nil:
	default:
		panic(fmt.Sprintf("proto: unexpected type %T in oneof", x))
	}
	return n
}

// Enumeration of all JobStates
type JobState struct {
}

func (m *JobState) Reset()                    { *m = JobState{} }
func (m *JobState) String() string            { return proto.CompactTextString(m) }
func (*JobState) ProtoMessage()               {}
func (*JobState) Descriptor() ([]byte, []int) { return fileDescriptor0, []int{11} }

func init() {
	proto.RegisterType((*PrepareJobRequest)(nil), "org.apache.beam.model.job_management.v1.PrepareJobRequest")
	proto.RegisterType((*PrepareJobResponse)(nil), "org.apache.beam.model.job_management.v1.PrepareJobResponse")
	proto.RegisterType((*RunJobRequest)(nil), "org.apache.beam.model.job_management.v1.RunJobRequest")
	proto.RegisterType((*RunJobResponse)(nil), "org.apache.beam.model.job_management.v1.RunJobResponse")
	proto.RegisterType((*CancelJobRequest)(nil), "org.apache.beam.model.job_management.v1.CancelJobRequest")
	proto.RegisterType((*CancelJobResponse)(nil), "org.apache.beam.model.job_management.v1.CancelJobResponse")
	proto.RegisterType((*GetJobStateRequest)(nil), "org.apache.beam.model.job_management.v1.GetJobStateRequest")
	proto.RegisterType((*GetJobStateResponse)(nil), "org.apache.beam.model.job_management.v1.GetJobStateResponse")
	proto.RegisterType((*JobMessagesRequest)(nil), "org.apache.beam.model.job_management.v1.JobMessagesRequest")
	proto.RegisterType((*JobMessage)(nil), "org.apache.beam.model.job_management.v1.JobMessage")
	proto.RegisterType((*JobMessagesResponse)(nil), "org.apache.beam.model.job_management.v1.JobMessagesResponse")
	proto.RegisterType((*JobState)(nil), "org.apache.beam.model.job_management.v1.JobState")
	proto.RegisterEnum("org.apache.beam.model.job_management.v1.JobMessage_MessageImportance", JobMessage_MessageImportance_name, JobMessage_MessageImportance_value)
	proto.RegisterEnum("org.apache.beam.model.job_management.v1.JobState_Enum", JobState_Enum_name, JobState_Enum_value)
}

// Reference imports to suppress errors if they are not otherwise used.
var _ context.Context
var _ grpc.ClientConn

// This is a compile-time assertion to ensure that this generated file
// is compatible with the grpc package it is being compiled against.
const _ = grpc.SupportPackageIsVersion4

// Client API for JobService service

type JobServiceClient interface {
	// Prepare a job for execution. The job will not be executed until a call is made to run with the
	// returned preparationId.
	Prepare(ctx context.Context, in *PrepareJobRequest, opts ...grpc.CallOption) (*PrepareJobResponse, error)
	// Submit the job for execution
	Run(ctx context.Context, in *RunJobRequest, opts ...grpc.CallOption) (*RunJobResponse, error)
	// Get the current state of the job
	GetState(ctx context.Context, in *GetJobStateRequest, opts ...grpc.CallOption) (*GetJobStateResponse, error)
	// Cancel the job
	Cancel(ctx context.Context, in *CancelJobRequest, opts ...grpc.CallOption) (*CancelJobResponse, error)
	// Subscribe to a stream of state changes of the job, will immediately return the current state of the job as the first response.
	GetStateStream(ctx context.Context, in *GetJobStateRequest, opts ...grpc.CallOption) (JobService_GetStateStreamClient, error)
	// Subscribe to a stream of state changes and messages from the job
	GetMessageStream(ctx context.Context, in *JobMessagesRequest, opts ...grpc.CallOption) (JobService_GetMessageStreamClient, error)
}

type jobServiceClient struct {
	cc *grpc.ClientConn
}

func NewJobServiceClient(cc *grpc.ClientConn) JobServiceClient {
	return &jobServiceClient{cc}
}

func (c *jobServiceClient) Prepare(ctx context.Context, in *PrepareJobRequest, opts ...grpc.CallOption) (*PrepareJobResponse, error) {
	out := new(PrepareJobResponse)
	err := grpc.Invoke(ctx, "/org.apache.beam.model.job_management.v1.JobService/Prepare", in, out, c.cc, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *jobServiceClient) Run(ctx context.Context, in *RunJobRequest, opts ...grpc.CallOption) (*RunJobResponse, error) {
	out := new(RunJobResponse)
	err := grpc.Invoke(ctx, "/org.apache.beam.model.job_management.v1.JobService/Run", in, out, c.cc, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *jobServiceClient) GetState(ctx context.Context, in *GetJobStateRequest, opts ...grpc.CallOption) (*GetJobStateResponse, error) {
	out := new(GetJobStateResponse)
	err := grpc.Invoke(ctx, "/org.apache.beam.model.job_management.v1.JobService/GetState", in, out, c.cc, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *jobServiceClient) Cancel(ctx context.Context, in *CancelJobRequest, opts ...grpc.CallOption) (*CancelJobResponse, error) {
	out := new(CancelJobResponse)
	err := grpc.Invoke(ctx, "/org.apache.beam.model.job_management.v1.JobService/Cancel", in, out, c.cc, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *jobServiceClient) GetStateStream(ctx context.Context, in *GetJobStateRequest, opts ...grpc.CallOption) (JobService_GetStateStreamClient, error) {
	stream, err := grpc.NewClientStream(ctx, &_JobService_serviceDesc.Streams[0], c.cc, "/org.apache.beam.model.job_management.v1.JobService/GetStateStream", opts...)
	if err != nil {
		return nil, err
	}
	x := &jobServiceGetStateStreamClient{stream}
	if err := x.ClientStream.SendMsg(in); err != nil {
		return nil, err
	}
	if err := x.ClientStream.CloseSend(); err != nil {
		return nil, err
	}
	return x, nil
}

type JobService_GetStateStreamClient interface {
	Recv() (*GetJobStateResponse, error)
	grpc.ClientStream
}

type jobServiceGetStateStreamClient struct {
	grpc.ClientStream
}

func (x *jobServiceGetStateStreamClient) Recv() (*GetJobStateResponse, error) {
	m := new(GetJobStateResponse)
	if err := x.ClientStream.RecvMsg(m); err != nil {
		return nil, err
	}
	return m, nil
}

func (c *jobServiceClient) GetMessageStream(ctx context.Context, in *JobMessagesRequest, opts ...grpc.CallOption) (JobService_GetMessageStreamClient, error) {
	stream, err := grpc.NewClientStream(ctx, &_JobService_serviceDesc.Streams[1], c.cc, "/org.apache.beam.model.job_management.v1.JobService/GetMessageStream", opts...)
	if err != nil {
		return nil, err
	}
	x := &jobServiceGetMessageStreamClient{stream}
	if err := x.ClientStream.SendMsg(in); err != nil {
		return nil, err
	}
	if err := x.ClientStream.CloseSend(); err != nil {
		return nil, err
	}
	return x, nil
}

type JobService_GetMessageStreamClient interface {
	Recv() (*JobMessagesResponse, error)
	grpc.ClientStream
}

type jobServiceGetMessageStreamClient struct {
	grpc.ClientStream
}

func (x *jobServiceGetMessageStreamClient) Recv() (*JobMessagesResponse, error) {
	m := new(JobMessagesResponse)
	if err := x.ClientStream.RecvMsg(m); err != nil {
		return nil, err
	}
	return m, nil
}

// Server API for JobService service

type JobServiceServer interface {
	// Prepare a job for execution. The job will not be executed until a call is made to run with the
	// returned preparationId.
	Prepare(context.Context, *PrepareJobRequest) (*PrepareJobResponse, error)
	// Submit the job for execution
	Run(context.Context, *RunJobRequest) (*RunJobResponse, error)
	// Get the current state of the job
	GetState(context.Context, *GetJobStateRequest) (*GetJobStateResponse, error)
	// Cancel the job
	Cancel(context.Context, *CancelJobRequest) (*CancelJobResponse, error)
	// Subscribe to a stream of state changes of the job, will immediately return the current state of the job as the first response.
	GetStateStream(*GetJobStateRequest, JobService_GetStateStreamServer) error
	// Subscribe to a stream of state changes and messages from the job
	GetMessageStream(*JobMessagesRequest, JobService_GetMessageStreamServer) error
}

func RegisterJobServiceServer(s *grpc.Server, srv JobServiceServer) {
	s.RegisterService(&_JobService_serviceDesc, srv)
}

func _JobService_Prepare_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(PrepareJobRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(JobServiceServer).Prepare(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/org.apache.beam.model.job_management.v1.JobService/Prepare",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(JobServiceServer).Prepare(ctx, req.(*PrepareJobRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _JobService_Run_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(RunJobRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(JobServiceServer).Run(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/org.apache.beam.model.job_management.v1.JobService/Run",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(JobServiceServer).Run(ctx, req.(*RunJobRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _JobService_GetState_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(GetJobStateRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(JobServiceServer).GetState(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/org.apache.beam.model.job_management.v1.JobService/GetState",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(JobServiceServer).GetState(ctx, req.(*GetJobStateRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _JobService_Cancel_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(CancelJobRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(JobServiceServer).Cancel(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/org.apache.beam.model.job_management.v1.JobService/Cancel",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(JobServiceServer).Cancel(ctx, req.(*CancelJobRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _JobService_GetStateStream_Handler(srv interface{}, stream grpc.ServerStream) error {
	m := new(GetJobStateRequest)
	if err := stream.RecvMsg(m); err != nil {
		return err
	}
	return srv.(JobServiceServer).GetStateStream(m, &jobServiceGetStateStreamServer{stream})
}

type JobService_GetStateStreamServer interface {
	Send(*GetJobStateResponse) error
	grpc.ServerStream
}

type jobServiceGetStateStreamServer struct {
	grpc.ServerStream
}

func (x *jobServiceGetStateStreamServer) Send(m *GetJobStateResponse) error {
	return x.ServerStream.SendMsg(m)
}

func _JobService_GetMessageStream_Handler(srv interface{}, stream grpc.ServerStream) error {
	m := new(JobMessagesRequest)
	if err := stream.RecvMsg(m); err != nil {
		return err
	}
	return srv.(JobServiceServer).GetMessageStream(m, &jobServiceGetMessageStreamServer{stream})
}

type JobService_GetMessageStreamServer interface {
	Send(*JobMessagesResponse) error
	grpc.ServerStream
}

type jobServiceGetMessageStreamServer struct {
	grpc.ServerStream
}

func (x *jobServiceGetMessageStreamServer) Send(m *JobMessagesResponse) error {
	return x.ServerStream.SendMsg(m)
}

var _JobService_serviceDesc = grpc.ServiceDesc{
	ServiceName: "org.apache.beam.model.job_management.v1.JobService",
	HandlerType: (*JobServiceServer)(nil),
	Methods: []grpc.MethodDesc{
		{
			MethodName: "Prepare",
			Handler:    _JobService_Prepare_Handler,
		},
		{
			MethodName: "Run",
			Handler:    _JobService_Run_Handler,
		},
		{
			MethodName: "GetState",
			Handler:    _JobService_GetState_Handler,
		},
		{
			MethodName: "Cancel",
			Handler:    _JobService_Cancel_Handler,
		},
	},
	Streams: []grpc.StreamDesc{
		{
			StreamName:    "GetStateStream",
			Handler:       _JobService_GetStateStream_Handler,
			ServerStreams: true,
		},
		{
			StreamName:    "GetMessageStream",
			Handler:       _JobService_GetMessageStream_Handler,
			ServerStreams: true,
		},
	},
	Metadata: "beam_job_api.proto",
}

func init() { proto.RegisterFile("beam_job_api.proto", fileDescriptor0) }

var fileDescriptor0 = []byte{
	// 931 bytes of a gzipped FileDescriptorProto
	0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0xff, 0xc4, 0x56, 0x41, 0x6f, 0xe3, 0x44,
	0x14, 0xae, 0xdb, 0x34, 0x4d, 0x5e, 0x9b, 0xd4, 0x9d, 0x52, 0x35, 0x1b, 0x01, 0x5a, 0x8c, 0x60,
	0x17, 0xad, 0xe4, 0xdd, 0x76, 0x25, 0x56, 0xec, 0x72, 0x71, 0x62, 0x6f, 0xd6, 0x51, 0x9b, 0x44,
	0xe3, 0x54, 0x48, 0x70, 0x30, 0xe3, 0x64, 0x36, 0xb8, 0xd4, 0x1e, 0x63, 0x4f, 0xa2, 0xbd, 0x21,
	0x21, 0x71, 0x44, 0xfc, 0x01, 0xfe, 0x00, 0x27, 0x38, 0x70, 0xe3, 0x1f, 0xf1, 0x17, 0xb8, 0xa0,
	0x19, 0x8f, 0xdb, 0xa4, 0xed, 0xaa, 0x69, 0x11, 0xe2, 0x94, 0x99, 0xf7, 0xde, 0xf7, 0xcd, 0x37,
	0xef, 0x3d, 0xbf, 0x09, 0xa0, 0x80, 0x92, 0xc8, 0x3f, 0x65, 0x81, 0x4f, 0x92, 0xd0, 0x4c, 0x52,
	0xc6, 0x19, 0x7a, 0xc0, 0xd2, 0x89, 0x49, 0x12, 0x32, 0xfa, 0x86, 0x9a, 0xc2, 0x6d, 0x46, 0x6c,
	0x4c, 0xcf, 0x4c, 0x11, 0x14, 0x91, 0x98, 0x4c, 0x68, 0x44, 0x63, 0x6e, 0xce, 0x0e, 0x9a, 0x7b,
	0x12, 0x9c, 0x4e, 0xe3, 0x98, 0xa6, 0x17, 0xf8, 0xe6, 0x36, 0x8d, 0xc7, 0x09, 0x0b, 0x63, 0x9e,
	0x29, 0xc3, 0xbb, 0x13, 0xc6, 0x26, 0x67, 0xf4, 0xb1, 0xdc, 0x05, 0xd3, 0xd7, 0x8f, 0x33, 0x9e,
	0x4e, 0x47, 0x3c, 0xf7, 0x1a, 0x7f, 0x6a, 0xb0, 0x33, 0x48, 0x69, 0x42, 0x52, 0xda, 0x65, 0x01,
	0xa6, 0xdf, 0x4d, 0x69, 0xc6, 0x51, 0x07, 0x2a, 0x49, 0x98, 0xd0, 0xb3, 0x30, 0xa6, 0x0d, 0xed,
	0xbe, 0xf6, 0x70, 0xf3, 0xf0, 0x91, 0x79, 0xbd, 0xae, 0x22, 0xcc, 0x9c, 0x1d, 0x98, 0x03, 0xb5,
	0xc6, 0xe7, 0x60, 0xd4, 0x02, 0xbd, 0x58, 0xfb, 0x2c, 0xe1, 0x21, 0x8b, 0xb3, 0xc6, 0xaa, 0x24,
	0xdc, 0x37, 0x73, 0x5d, 0x66, 0xa1, 0xcb, 0xf4, 0xa4, 0x2e, 0xbc, 0x5d, 0x00, 0xfa, 0x79, 0x3c,
	0xba, 0x07, 0x15, 0x71, 0xfb, 0x98, 0x44, 0xb4, 0xb1, 0x76, 0x5f, 0x7b, 0x58, 0xc5, 0x1b, 0xa7,
	0x2c, 0xe8, 0x91, 0x88, 0x1a, 0xbf, 0x6b, 0x80, 0xe6, 0xd5, 0x67, 0x09, 0x8b, 0x33, 0x8a, 0x3e,
	0x82, 0x7a, 0x22, 0xad, 0x44, 0x30, 0xf8, 0xe1, 0x58, 0x5e, 0xa2, 0x8a, 0x6b, 0x73, 0x56, 0x77,
	0x8c, 0x32, 0xb8, 0x47, 0x52, 0x1e, 0xbe, 0x26, 0x23, 0xee, 0x67, 0x9c, 0x4c, 0xc2, 0x78, 0xe2,
	0x17, 0xd9, 0x53, 0x2a, 0x9f, 0x2d, 0x71, 0x6d, 0x2b, 0x09, 0x3d, 0x9a, 0xce, 0xc2, 0x11, 0xb5,
	0x69, 0x36, 0x4a, 0xc3, 0x84, 0xb3, 0x14, 0xef, 0x17, 0xcc, 0x5e, 0x4e, 0xec, 0x28, 0x5e, 0xe3,
	0x2b, 0xa8, 0xe1, 0x69, 0x3c, 0x97, 0xeb, 0x25, 0xc5, 0x7e, 0x08, 0xb5, 0x42, 0x23, 0x67, 0xdf,
	0xd2, 0x58, 0x0a, 0xac, 0xe2, 0x2d, 0x65, 0x1c, 0x0a, 0x9b, 0xf1, 0x00, 0xea, 0x05, 0xb9, 0x4a,
	0xc5, 0x1e, 0x94, 0x45, 0xf2, 0xce, 0x59, 0xd7, 0x4f, 0x59, 0xe0, 0x8e, 0x8d, 0x4f, 0x40, 0x6f,
	0x93, 0x78, 0x44, 0xcf, 0xe6, 0x84, 0xbc, 0x25, 0x94, 0xc0, 0xce, 0x5c, 0xa8, 0xa2, 0x3d, 0x82,
	0xf5, 0x8c, 0x13, 0x9e, 0x77, 0x47, 0xfd, 0xf0, 0x53, 0x73, 0xc9, 0xae, 0x35, 0xbb, 0x2c, 0xf0,
	0x04, 0xd0, 0x74, 0xe2, 0x69, 0x84, 0x73, 0x12, 0xe3, 0x11, 0xa0, 0x0e, 0xe5, 0x85, 0xeb, 0x06,
	0x3d, 0x23, 0xd8, 0x5d, 0x08, 0xfe, 0xaf, 0x14, 0x75, 0x59, 0x70, 0x4c, 0xb3, 0x8c, 0x4c, 0x68,
	0x76, 0x83, 0xa2, 0xbf, 0x57, 0x01, 0x2e, 0xa2, 0xd1, 0x7b, 0x00, 0x51, 0xbe, 0xbc, 0x88, 0xac,
	0x2a, 0x8b, 0x3b, 0x46, 0x08, 0x4a, 0x3c, 0x8c, 0xa8, 0xaa, 0x9f, 0x5c, 0x23, 0x0a, 0x10, 0x46,
	0x09, 0x4b, 0xb9, 0x48, 0xb4, 0x6c, 0xf2, 0xfa, 0xa1, 0x73, 0x9b, 0x1b, 0xa8, 0xb3, 0x4d, 0xf5,
	0xeb, 0x9e, 0x93, 0xe1, 0x39, 0x62, 0xf4, 0x01, 0x6c, 0x15, 0xca, 0x38, 0x7d, 0xc3, 0x1b, 0x25,
	0x29, 0x61, 0x53, 0xd9, 0x86, 0xf4, 0x0d, 0x37, 0x7e, 0xd3, 0x60, 0xe7, 0x0a, 0x09, 0x32, 0xe0,
	0xfd, 0x63, 0xc7, 0xf3, 0xac, 0x8e, 0xe3, 0xbb, 0xc7, 0x83, 0x3e, 0x1e, 0x5a, 0xbd, 0xb6, 0xe3,
	0x9f, 0xf4, 0xbc, 0x81, 0xd3, 0x76, 0x5f, 0xba, 0x8e, 0xad, 0xaf, 0xa0, 0x3d, 0xd8, 0xe9, 0xf6,
	0x5b, 0x7e, 0x11, 0x67, 0x3b, 0xad, 0x93, 0x8e, 0xae, 0xa1, 0x06, 0xbc, 0xb3, 0x68, 0x1e, 0x5a,
	0xee, 0x91, 0x63, 0xeb, 0xab, 0x97, 0x01, 0x2d, 0xcb, 0x73, 0xdb, 0xfa, 0x1a, 0xda, 0x87, 0xdd,
	0x79, 0xf3, 0x17, 0x16, 0xee, 0xb9, 0xbd, 0x8e, 0x5e, 0xba, 0x1c, 0xef, 0x60, 0xdc, 0xc7, 0xfa,
	0xba, 0xf1, 0x97, 0x06, 0xbb, 0x0b, 0xb5, 0x52, 0x0d, 0xf1, 0x35, 0xe8, 0xc5, 0x65, 0x53, 0x65,
	0x53, 0xb3, 0xec, 0xe9, 0x1d, 0x32, 0xfb, 0x6a, 0x05, 0x6f, 0x2b, 0xba, 0xf3, 0x13, 0x28, 0xd4,
	0x65, 0xb7, 0x5c, 0xf0, 0xe7, 0x43, 0xe3, 0xf3, 0xa5, 0xf9, 0xaf, 0x69, 0xe4, 0x57, 0x2b, 0xb8,
	0x96, 0xcd, 0x1b, 0x5a, 0x00, 0x95, 0xe2, 0x00, 0xe3, 0x57, 0x0d, 0x2a, 0x05, 0xc2, 0xf8, 0x45,
	0x83, 0x92, 0x68, 0x5a, 0xb4, 0x0d, 0x9b, 0x8b, 0xb5, 0xd8, 0x84, 0x0d, 0x6f, 0xd8, 0x1f, 0x0c,
	0x1c, 0x5b, 0xd7, 0xc4, 0x06, 0x9f, 0xf4, 0x64, 0x12, 0x57, 0x51, 0x05, 0x4a, 0x76, 0xbf, 0xe7,
	0xe8, 0x6b, 0x08, 0xa0, 0xfc, 0x32, 0x2f, 0x45, 0x09, 0xd5, 0xa0, 0xda, 0x16, 0x25, 0x3d, 0x12,
	0xdb, 0x75, 0x81, 0x38, 0x19, 0xd8, 0xd6, 0xd0, 0xb1, 0xf5, 0x32, 0xda, 0x82, 0x8a, 0x8d, 0x2d,
	0x57, 0xe2, 0x37, 0x84, 0x4b, 0xee, 0x1c, 0x5b, 0xaf, 0x08, 0x97, 0x37, 0xb4, 0xf0, 0x50, 0xb8,
	0xaa, 0xa8, 0x0e, 0xa0, 0x48, 0xc4, 0x1e, 0x0e, 0xff, 0x28, 0xcb, 0xcf, 0x42, 0xcd, 0x46, 0xf4,
	0x83, 0x06, 0x1b, 0x6a, 0x56, 0xa3, 0xe7, 0x4b, 0x67, 0xe8, 0xca, 0xdb, 0xd4, 0x7c, 0x71, 0x27,
	0xac, 0x2a, 0xd9, 0x0c, 0xd6, 0xf0, 0x34, 0x46, 0xcb, 0x4f, 0x87, 0x85, 0x59, 0xdd, 0x7c, 0x76,
	0x6b, 0x9c, 0x3a, 0xf7, 0x47, 0x0d, 0x2a, 0x1d, 0xca, 0x65, 0xdd, 0xd0, 0x8b, 0xbb, 0xf5, 0x47,
	0x2e, 0xe1, 0x5f, 0x35, 0x17, 0xfa, 0x1e, 0xca, 0xf9, 0x30, 0x47, 0x9f, 0x2d, 0xcd, 0x73, 0xf9,
	0xa1, 0x68, 0x3e, 0xbf, 0x0b, 0x54, 0x09, 0xf8, 0x49, 0x83, 0x7a, 0x91, 0x08, 0x8f, 0xa7, 0x94,
	0x44, 0xff, 0x63, 0x3a, 0x9e, 0x68, 0xe8, 0x67, 0x0d, 0xf4, 0x0e, 0xe5, 0xea, 0x2b, 0xbf, 0xb5,
	0xa2, 0xab, 0x8f, 0xc4, 0x2d, 0x14, 0x5d, 0x33, 0xb5, 0x9e, 0x68, 0xad, 0x16, 0x7c, 0xfc, 0x56,
	0x82, 0x05, 0x7c, 0xab, 0xdc, 0x65, 0x81, 0x95, 0x84, 0x5f, 0xea, 0x0b, 0x1e, 0x7f, 0x76, 0x10,
	0x94, 0xe5, 0x9f, 0xaa, 0xa7, 0xff, 0x04, 0x00, 0x00, 0xff, 0xff, 0x20, 0x71, 0x77, 0xfc, 0x61,
	0x0a, 0x00, 0x00,
}

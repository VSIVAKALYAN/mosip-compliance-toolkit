package io.mosip.compliance.toolkit.validators;

import java.io.IOException;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.mosip.compliance.toolkit.constants.AppConstants;
import io.mosip.compliance.toolkit.constants.CertificationTypes;
import io.mosip.compliance.toolkit.constants.DeviceStatus;
import io.mosip.compliance.toolkit.constants.MethodName;
import io.mosip.compliance.toolkit.constants.PartnerTypes;
import io.mosip.compliance.toolkit.dto.testcases.ValidationInputDto;
import io.mosip.compliance.toolkit.dto.testcases.ValidationResultDto;
import io.mosip.compliance.toolkit.exceptions.ToolkitException;
import io.mosip.compliance.toolkit.util.StringUtil;

@Component
public class SignatureValidator extends SBIValidator {

	private static final String CERTIFICATION = "certification";
	@Value("${mosip.service.keymanager.verifyCertificateTrust.url}")
	private String keyManagerTrustUrl;

	@Override
	public ValidationResultDto validateResponse(ValidationInputDto inputDto) {
		ValidationResultDto validationResultDto = new ValidationResultDto();
		try {
			if (validateMethodName(inputDto.getMethodName())) {
				if (Objects.nonNull(inputDto.getMethodResponse())) {
					switch (MethodName.fromCode(inputDto.getMethodName())) {
					case DEVICE:
						validationResultDto = validateDiscoverySignature(inputDto);
						break;
					case INFO:
						validationResultDto = validateDeviceSignature(inputDto);
						break;
					case CAPTURE:
						validationResultDto = validateCaptureSignature(inputDto);
						break;
					case RCAPTURE:
						validationResultDto = validateRCaptureSignature(inputDto);
						break;
					default:
						validationResultDto.setStatus(AppConstants.FAILURE);
						validationResultDto.setDescription("Method not supported");
						break;
					}
				} else {
					validationResultDto.setStatus(AppConstants.FAILURE);
					validationResultDto.setDescription("Response is empty");
				}
			}
		} catch (ToolkitException e) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto.setDescription(e.getLocalizedMessage());
		} catch (Exception e) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto.setDescription(e.getLocalizedMessage());
		}
		return validationResultDto;
	}

	private ValidationResultDto validateDiscoverySignature(ValidationInputDto inputDto) {
		ValidationResultDto validationResultDto = new ValidationResultDto();
		try {
			ArrayNode arrDiscoverResponse = (ArrayNode) objectMapper.readValue(inputDto.getMethodResponse(),
					ArrayNode.class);
			ObjectNode discoveryInfoNode = (ObjectNode) arrDiscoverResponse.get(0);

			String digitalId = StringUtil
					.toUtf8String(StringUtil.base64UrlDecode(discoveryInfoNode.get(DIGITAL_ID).asText()));
			validationResultDto = validateUnsignedDigitalID(digitalId);
		} catch (Exception e) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto
					.setDescription("SignatureValidator failure - " + "with Message - " + e.getLocalizedMessage());
		}
		return validationResultDto;
	}

	private ValidationResultDto validateUnsignedDigitalID(String digitalId) throws Exception {
		ValidationResultDto validationResultDto = new ValidationResultDto();
		ObjectNode digitalIdDto = objectMapper.readValue(digitalId, ObjectNode.class);
		if (Objects.isNull(digitalIdDto) || digitalIdDto.get(DEVICE_TYPE).isNull()
				|| digitalIdDto.get(DEVICE_SUB_TYPE).isNull()) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto.setDescription("Unsigned Digital ID validation failed");
		} else {
			validationResultDto.setStatus(AppConstants.SUCCESS);
			validationResultDto.setDescription("Unsigned Digital ID validation is successful");
		}
		return validationResultDto;
	}

	private ValidationResultDto validateDeviceSignature(ValidationInputDto inputDto) {
		ValidationResultDto validationResultDto = new ValidationResultDto();
		try {
			ArrayNode arrDeviceInfoResponse = (ArrayNode) objectMapper.readValue(inputDto.getMethodResponse(),
					ArrayNode.class);

			for (int deviceIndex = 0; deviceIndex < arrDeviceInfoResponse.size(); deviceIndex++) {
				ObjectNode deviceInfoNode = (ObjectNode) arrDeviceInfoResponse.get(deviceIndex);
				if (isDeviceInfoUnSigned(deviceInfoNode)) {
					validationResultDto = validateUnSignedDeviceInfo(deviceInfoNode);
				} else {
					validationResultDto = validateSignedDeviceInfo(deviceInfoNode);
				}
				if (validationResultDto.getStatus().equals(AppConstants.FAILURE))
					break;
			}
		} catch (Exception e) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto
					.setDescription("SignatureValidator failure - " + "with Message - " + e.getLocalizedMessage());
		}
		return validationResultDto;
	}

	private ValidationResultDto validateUnSignedDeviceInfo(ObjectNode objectNode) {
		ValidationResultDto validationResultDto = new ValidationResultDto();
		try {
			ObjectNode deviceInfoDto = getUnsignedDeviceInfo(objectNode.get(DEVICE_INFO).asText());
			DeviceStatus deviceStatus = DeviceStatus.fromCode(deviceInfoDto.get(DEVICE_STATUS).asText());

			if (deviceStatus == DeviceStatus.NOT_REGISTERED) {
				validationResultDto.setStatus(AppConstants.SUCCESS);
				validationResultDto.setDescription("Device is not registered");
			} else {
				validationResultDto.setStatus(AppConstants.FAILURE);
				validationResultDto.setDescription("Device is registered, so can not be unsigned");
			}
		} catch (ToolkitException e) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto
					.setDescription("SignatureValidator failure - " + "with Message - " + e.getLocalizedMessage());
		} catch (Exception e) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto
					.setDescription("SignatureValidator failure - " + "with Message - " + e.getLocalizedMessage());
		}
		return validationResultDto;
	}

	private ValidationResultDto validateSignedDeviceInfo(ObjectNode objectNode) {
		ValidationResultDto validationResultDto = new ValidationResultDto();
		try {
			String deviceInfo = objectNode.get(DEVICE_INFO).asText();
			validationResultDto = checkIfJWTSignatureIsValid(deviceInfo);
			if (validationResultDto.getStatus().equals(AppConstants.SUCCESS)) {
				validationResultDto = trustRootValidation(getCertificate(deviceInfo), PartnerTypes.DEVICE.toString(),
						TRUST_FOR_DEVICE_INFO);
				if (validationResultDto.getStatus().equals(AppConstants.SUCCESS)) {
					ObjectNode deviceInfoDecoded = objectMapper.readValue(getPayload(deviceInfo), ObjectNode.class);

					if (Objects.isNull(deviceInfoDecoded)) {
						validationResultDto.setStatus(AppConstants.FAILURE);
						validationResultDto.setDescription("Device info Decoded value is null");
					} else {
						validationResultDto = validateSignedDigitalId(deviceInfoDecoded.get(DIGITAL_ID).asText(),
								deviceInfoDecoded.get(CERTIFICATION).asText(), TRUST_FOR_DIGITAL_ID);
					}
				}
			}
		} catch (Exception e) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto
					.setDescription("SignatureValidator failure - " + "with Message - " + e.getLocalizedMessage());
		}
		return validationResultDto;
	}

	private ValidationResultDto validateCaptureSignature(ValidationInputDto inputDto) {
		ValidationResultDto validationResultDto = new ValidationResultDto();
		try {
			ObjectNode captureInfoResponse = (ObjectNode) objectMapper.readValue(inputDto.getMethodResponse(),
					ObjectNode.class);

			final JsonNode arrBiometricNodes = captureInfoResponse.get(BIOMETRICS);
			if (arrBiometricNodes.isArray()) {
				for (final JsonNode biometricNode : arrBiometricNodes) {
					String dataInfo = biometricNode.get(DATA).asText();
					validationResultDto = checkIfJWTSignatureIsValid(dataInfo);
					if (validationResultDto.getStatus().equals(AppConstants.SUCCESS)) {
						validationResultDto = trustRootValidation(getCertificate(dataInfo),
								PartnerTypes.DEVICE.toString(), TRUST_FOR_BIOMETRIC_INFO);

						if (validationResultDto.getStatus().equals(AppConstants.SUCCESS)) {
							String biometricData = getPayload(dataInfo);
							ObjectNode biometricDataNode = (ObjectNode) objectMapper.readValue(biometricData,
									ObjectNode.class);

							ObjectNode extraInfo = (ObjectNode) objectMapper.readValue(inputDto.getExtraInfoJson(),
									ObjectNode.class);
							String certificationType = extraInfo.get(CERTIFICATION_TYPE).asText();
							validationResultDto = validateSignedDigitalId(biometricDataNode.get(DIGITAL_ID).asText(),
									certificationType, TRUST_FOR_DIGITAL_ID);
						}
					}
					if (validationResultDto.getStatus().equals(AppConstants.FAILURE))
						break;
				}
			}
		} catch (Exception e) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto
					.setDescription("SignatureValidator failure - " + "with Message - " + e.getLocalizedMessage());
		}
		return validationResultDto;
	}

	private ValidationResultDto validateRCaptureSignature(ValidationInputDto inputDto) {
		ValidationResultDto validationResultDto = new ValidationResultDto();
		try {
			ObjectNode captureInfoResponse = (ObjectNode) objectMapper.readValue(inputDto.getMethodResponse(),
					ObjectNode.class);

			final JsonNode arrBiometricNodes = captureInfoResponse.get(BIOMETRICS);
			if (arrBiometricNodes.isArray()) {
				for (final JsonNode biometricNode : arrBiometricNodes) {
					String dataInfo = biometricNode.get(DATA).asText();
					validationResultDto = checkIfJWTSignatureIsValid(dataInfo);
					if (validationResultDto.getStatus().equals(AppConstants.SUCCESS)) {
						validationResultDto = trustRootValidation(getCertificate(dataInfo),
								PartnerTypes.DEVICE.toString(), TRUST_FOR_BIOMETRIC_INFO);

						if (validationResultDto.getStatus().equals(AppConstants.SUCCESS)) {
							String biometricData = getPayload(dataInfo);
							ObjectNode biometricDataNode = (ObjectNode) objectMapper.readValue(biometricData,
									ObjectNode.class);

							ObjectNode extraInfo = (ObjectNode) objectMapper.readValue(inputDto.getExtraInfoJson(),
									ObjectNode.class);
							String certificationType = extraInfo.get(CERTIFICATION_TYPE).asText();
							validationResultDto = validateSignedDigitalId(biometricDataNode.get(DIGITAL_ID).asText(),
									certificationType, TRUST_FOR_DIGITAL_ID);
						}
					}
					if (validationResultDto.getStatus().equals(AppConstants.FAILURE))
						break;
				}
			}
		} catch (Exception e) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto
					.setDescription("SignatureValidator failure - " + "with Message - " + e.getLocalizedMessage());
		}
		return validationResultDto;
	}

	private ValidationResultDto validateSignedDigitalId(String digitalId, String certificationType, String trustFor) {
		ValidationResultDto validationResultDto = new ValidationResultDto();
		try {
			CertificationTypes certification = CertificationTypes.fromCode(certificationType);
			validationResultDto = checkIfJWTSignatureIsValid(digitalId);
			if (validationResultDto.getStatus().equals(AppConstants.SUCCESS)) {
				if (certification == CertificationTypes.L0) {
					validationResultDto = trustRootValidation(getCertificate(digitalId), PartnerTypes.DEVICE.toString(),
							trustFor);
				} else if (certification == CertificationTypes.L1) {
					validationResultDto = trustRootValidation(getCertificate(digitalId), PartnerTypes.FTM.toString(),
							trustFor);
				}
			}
		} catch (ToolkitException e) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto.setDescription(e.getLocalizedMessage());
		} catch (Exception e) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto.setDescription(e.getLocalizedMessage());
		}
		return validationResultDto;
	}

	public ValidationResultDto trustRootValidation(String certificateData, String partnerType, String trustFor)
			throws IOException {
		ValidationResultDto validationResultDto = new ValidationResultDto();
		DeviceValidatorDto deviceValidatorDto = new DeviceValidatorDto();
		deviceValidatorDto.setRequesttime(getCurrentDateAndTimeForAPI());
		DeviceTrustRequestDto trustRequest = new DeviceTrustRequestDto();

		trustRequest.setCertificateData(getCertificateData(certificateData));
		trustRequest.setPartnerDomain(partnerType);
		deviceValidatorDto.setRequest(trustRequest);

		try {
			io.restassured.response.Response postResponse = getPostResponse(keyManagerTrustUrl, deviceValidatorDto);

			DeviceValidatorResponseDto deviceValidatorResponseDto = objectMapper
					.readValue(postResponse.getBody().asString(), DeviceValidatorResponseDto.class);

			if ((deviceValidatorResponseDto.getErrors() != null && deviceValidatorResponseDto.getErrors().size() > 0)
					|| (deviceValidatorResponseDto.getResponse().getStatus().equals("false"))) {
				validationResultDto.setStatus(AppConstants.FAILURE);
				validationResultDto.setDescription("Trust Validation Failed for [" + trustFor + "] >> PartnerType["
						+ partnerType + "] and CertificateData[" + certificateData + "]");
			} else {
				validationResultDto.setStatus(AppConstants.SUCCESS);
				validationResultDto.setDescription("Trust Root Validation is Successful");
			}
		} catch (Exception e) {
			validationResultDto.setStatus(AppConstants.FAILURE);
			validationResultDto.setDescription(
					"Exception in Trust root Validation - " + "with Message - " + e.getLocalizedMessage());
		}
		return validationResultDto;
	}
}

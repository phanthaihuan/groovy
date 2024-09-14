pipeline {
    agent any

    options {
        ansiColor('xterm')
    }

    parameters {
        string(name: 'INFRA_GIT_BRANCH', defaultValue: 'main', description: 'Git branch storing Terraform.')
    }

    environment {
        AWS_ACCESS_KEY_ID     = credentials('aws-secret-key-id')
        AWS_SECRET_ACCESS_KEY = credentials('aws-secret-access-key')    
        AWS_CREDENTIAL = 'aws-access'
        GIT_CREDENTIAL = 'github-access'
        INSTALLER_DIR = 'terraform'
        TF_VAR_SSH_PRIVATE_KEY = credentials('SSH_PRIVATE_KEY')
        TF_IN_AUTOMATION = 'true'
        ANSIBLE_HOST_KEY_CHECKING = 'False'
        ANSIBLE_CONFIG = 'ansible/build/ansible.cfg'
        EC2_PUBLIC_IP = ''
        EC2_INSTANCE_ID = ''
        EFS_ID = ''
        EFS_ACCESS_POINT_ID = ''
        EFS_MOUNT_TARGET_ID = ''
        EFS_DNS_NAME = ''
    }

    stages {
        // stage('Export AWS credentials') {
        //     steps {
        //         withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: AWS_CREDENTIAL, accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        //             sh """
        //                 export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}
        //                 export AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}
        //             """
        //         }
        //     }
        // }
        stage('Checkout SCM') {
            steps {
                deleteDir()
                dir(INSTALLER_DIR) {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: INFRA_GIT_BRANCH]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CleanBeforeCheckout']],
                        submoduleCfg: [],
                        userRemoteConfigs: [
                            [
                                credentialsId: GIT_CREDENTIAL,
                                url: 'git@github.com:phanthaihuan/terraform2024.git'
                            ]
                        ]
                    ])
                }
            }
        }

        stage('Init Provider') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'terraform init'
                }
            }
        }

        stage('Validate configuration files') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'terraform validate'
                    sh 'if [ $? -ne 0 ]; then exit 1; fi'
                }
            }
        }

        stage('Plan Resources') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'terraform plan'
                }
            }
        }

        stage('Apply Resources') {
            steps {
                script {
                    dir(INSTALLER_DIR) {
                        sh """
                            terraform apply -auto-approve
                        """
                    }
                }
            }
        }
        
        stage(' Wait for EC2 to be ready') {
            steps {
                dir(INSTALLER_DIR) {
                    script {
                        EC2_INSTANCE_ID  = sh(script: 'terraform output -raw ec2_instance_id', returnStdout: true).trim()
                        EC2_PUBLIC_IP = sh(script: 'terraform output -raw ec2_public_ip', returnStdout: true).trim()
                        
                        echo "${EC2_INSTANCE_ID}"
                        echo "${EC2_PUBLIC_IP}"
                        withEnv(["EC2_INSTANCE_ID=${EC2_INSTANCE_ID}", "EC2_PUBLIC_IP=${EC2_PUBLIC_IP}"]) {
                            sh '''
                                echo "$EC2_PUBLIC_IP" > ansible/ansible_inventory
                                cat ansible/ansible_inventory
                                #aws ec2 wait instance-status-ok --region ap-southeast-1 --instance-ids $EC2_INSTANCE_ID
                            '''
                        }                        
                    }
                }
            }
        }
        
        stage('Run Ansible') {
            steps {
                dir(INSTALLER_DIR) {
                    // Extract Terraform output to Ansible extraVars
                    script {
                        EFS_ID = sh(script: 'terraform output -raw efs_id', returnStdout: true).trim()
                        EFS_ACCESS_POINT_ID = sh(script: 'terraform output -raw efs_access_point_id', returnStdout: true).trim()
                        EFS_MOUNT_TARGET_ID = sh(script: 'terraform output -raw efs_mount_target_id', returnStdout: true).trim()
                        EFS_DNS_NAME = sh(script: 'terraform output -raw efs_dns_name', returnStdout: true).trim()
                        sh "printenv | sort"
                        ansiblePlaybook(
                        inventory: 'ansible/ansible_inventory',
                        playbook: 'ansible/build/playbook_build.yaml',
                        credentialsId: 'SSH_PRIVATE_KEY',
                        extraVars: [
                            efs__id: "${EFS_ID}",
                            efs_access_point_id: "${EFS_ACCESS_POINT_ID}",
                            efs_mount_target_id: "${EFS_MOUNT_TARGET_ID}",
                            efs_dns_name: "${EFS_DNS_NAME}"
                        ],
                        extras: '-v')                        
                    }
                }
            }
        }        
    }
}
